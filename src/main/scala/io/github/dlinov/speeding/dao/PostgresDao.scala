package io.github.dlinov.speeding.dao

import cats.effect._
import doobie._
import doobie.implicits._
import io.github.dlinov.speeding.dao.Dao.DaoError
import io.github.dlinov.speeding.model.{DriverInfo, Fine}
import org.slf4j.LoggerFactory

class PostgresDao(dbUri: String, user: String, password: String) extends Dao {

  import PostgresDao._

  private val logger = LoggerFactory.getLogger(classOf[PostgresDao])

  private val xa = {
    Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      dbUri,
      user,
      password
    )
  }

  override def createSchemaIfMissing(): IO[Int] = {
    qCountFines.query[Int].unique.transact(xa).attemptSql
      .flatMap(_.fold(
        _ ⇒ {
          qCountDrivers.query[Int].unique.transact(xa).attemptSql
            .flatMap(sqlResp ⇒ {
              val runCreateFinesTable = qCreateFinesTable.update.run
              sqlResp.fold(
                _ ⇒ {
                  (for {
                    _ ← qCreateDriversTable.update.run
                    _ ← runCreateFinesTable
                  } yield 0).transact(xa)
                },
                _ ⇒ runCreateFinesTable.transact(xa))
            })
        },
        IO.pure))
  }

  override def update(userId: Long, driverInfo: DriverInfo): DaoResp[DriverInfo] = {
    val fn = driverInfo.fullName
    val ls = driverInfo.licenseSeries
    val ln = driverInfo.licenseNumber
    (for {
      maybeExisting ← sql"SELECT id FROM drivers WHERE id = $userId"
        .query[Int]
        .option
      updResult ← maybeExisting.fold {
        sql"INSERT INTO drivers (id, full_name, license_series, license_number) VALUES ($userId, $fn, $ls, $ln)"
      } { _ ⇒
        sql"UPDATE drivers SET full_name = $fn, license_series = $ls, license_number = $ln WHERE id = $userId"
      }.update.run
    } yield {
      logger.debug(s"Update result: $updResult")
      Right(driverInfo): Either[DaoError, DriverInfo]
    }).transact(xa)
  }

  override def find(id: Long): DaoResp[Option[DriverInfo]] = {
    (for {
      maybeDriver ← sql"SELECT id, full_name, license_series, license_number FROM drivers WHERE id = $id"
        .query[DriverInfo]
        .option
    } yield {
      logger.debug(s"Find by id=$id: $maybeDriver")
      Right(maybeDriver)
    }).transact(xa)
  }

  override def findAll: DaoResp[Seq[DriverInfo]] = {
    (for {
      all ← sql"SELECT id, full_name, license_series, license_number FROM drivers"
        .query[DriverInfo]
        .to[List]
    } yield {
      logger.debug(s"Find all results: $all")
      Right(all)
    }).transact(xa)
  }

  override def createFines(fines: Seq[Fine]): DaoResp[Seq[Fine]] = {
    def createFine(fine: Fine): DaoResp[Fine] = {
      (for {
        _ ← sql"INSERT INTO fines (id, driver_id, date_time, is_active) VALUES (${fine.id}, ${fine.driverId}, ${fine.timestamp}, ${fine.isActive});"
          .update.run
      } yield {
        logger.debug(s"$fine was saved to db")
        Right(fine)
      }).transact(xa)
    }

    def inner(acc: Seq[Fine], rem: Seq[Fine]): DaoResp[Seq[Fine]] = {
      rem.headOption
        .fold[DaoResp[Seq[Fine]]] {
          IO(Right[DaoError, Seq[Fine]](acc))
        } { f ⇒ {
            val saveResult = createFine(f).unsafeRunSync()
            saveResult.fold(e ⇒ IO(Left(e)), x ⇒ inner(acc :+ f, rem.tail))
          }
        }
    }

    inner(Seq.empty, fines)
  }

  override def findFine(id: Long): DaoResp[Option[Fine]] = {
    (for {
      maybeDriver ← sql"SELECT id, date_time, is_active FROM fines WHERE id = $id"
        .query[Fine]
        .option
    } yield {
      logger.debug(s"Find by id=$id: $maybeDriver")
      Right(maybeDriver)
    }).transact(xa)
  }

  override def findAllDriverFines(driverId: Long): DaoResp[Seq[Fine]] = {
    (for {
      all ← sql"SELECT id, date_time, is_active FROM fines WHERE driver_id = $driverId"
        .query[Fine]
        .to[List]
    } yield {
      logger.debug(s"Find driver $driverId fines results: $all")
      Right(all)
    }).transact(xa)
  }

  override def findUnpaidDriverFines(driverId: Long): DaoResp[Seq[Fine]] = {
    (for {
      all ← sql"SELECT id, driver_id, date_time, is_active FROM fines WHERE driver_id = $driverId and is_active IS TRUE"
        .query[Fine]
        .to[List]
    } yield {
      logger.debug(s"Find driver $driverId fines results: $all")
      Right(all)
    }).transact(xa)
  }

  override def setFinesPaid(fineIds: Seq[Long]): DaoResp[Int] = {
    def setFinePaid(id: Long): DaoResp[Long] = {
      (for {
        _ ← sql"UPDATE fines SET is_active = FALSE WHERE id = $id;"
          .update.run
      } yield {
        Right(id)
      }).transact(xa)
    }

    def inner(acc: Int, rem: Seq[Long]): DaoResp[Int] = {
      rem.headOption.fold[DaoResp[Int]] {
        IO.pure(Right(acc))
      } { id ⇒
        setFinePaid(id).unsafeRunSync()
          .fold(
            e ⇒ IO(Left(e)),
            _ ⇒ inner(acc + 1, rem.tail))
      }
    }

    inner(0, fineIds)
  }
}

object PostgresDao {

  val qCountFines = sql"SELECT COUNT(*) from fines"
  val qCountDrivers = sql"SELECT COUNT(*) from drivers"

  val qCreateFinesTable = sql"CREATE TABLE fines (id bigint NOT NULL, driver_id bigint NOT NULL, date_time character varying(255) NOT NULL, is_active boolean NOT NULL, CONSTRAINT fines_pkey PRIMARY KEY (id), CONSTRAINT driver_id_fkey FOREIGN KEY (driver_id) REFERENCES drivers (id)) WITH (OIDS=FALSE);"
  val qCreateDriversTable = sql"CREATE TABLE drivers (id bigint NOT NULL, full_name character varying(255), license_series character varying(15), license_number character varying(15), CONSTRAINT drivers_pkey PRIMARY KEY (id)) WITH (OIDS=FALSE);"

}
