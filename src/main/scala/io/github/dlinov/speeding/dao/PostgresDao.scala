package io.github.dlinov.speeding.dao

import cats.effect._
import doobie._
import doobie.implicits._
import io.github.dlinov.speeding.dao.Dao.DaoError
import io.github.dlinov.speeding.model.DriverInfo
import org.slf4j.LoggerFactory

import scala.util.Try

class PostgresDao(dbUri: String, user: String, password: String) extends Dao {

  private val logger = LoggerFactory.getLogger(classOf[PostgresDao])

  private val xa = {
    Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      dbUri,
      user,
      password
    )
  }

  override def update(msgSource: Long, driverInfo: DriverInfo): DaoResp[DriverInfo] = {
    val fn = driverInfo.fullName
    val ls = driverInfo.licenseSeries
    val ln = driverInfo.licenseNumber
    (for {
      maybeExisting ← sql"SELECT id FROM drivers WHERE id = $msgSource"
        .query[Int]
        .option
      updResult ← maybeExisting.fold {
        sql"INSERT INTO drivers (id, full_name, license_series, license_number) VALUES ($msgSource, $fn, $ls, $ln)"
      } { _ ⇒
        sql"UPDATE drivers SET full_name = $fn, license_series = $ls, license_number = $ln WHERE id = $msgSource"
      }.update.run
    } yield {
      logger.debug(s"Update result: $updResult")
      Right(driverInfo): Either[DaoError, DriverInfo]
    }).transact(xa)
  }

  override def find(id: Long): DaoResp[Option[DriverInfo]] = {
    (for {
      maybeDriver ← sql"SELECT full_name, license_series, license_number FROM drivers WHERE id = $id"
        .query[DriverInfo]
        .option
    } yield {
      logger.debug(s"Find by id=$id: $maybeDriver")
      Right(maybeDriver)
    }).transact(xa)
  }

  override def findAll: DaoResp[Seq[(Long, DriverInfo)]] = {
    (for {
      all ← sql"SELECT id, full_name, license_series, license_number FROM drivers"
        .query[(Long, DriverInfo)]
        .to[List]
    } yield {
      logger.debug(s"Find all results: $all")
      Right(all)
    }).transact(xa)
  }

  override def createSchemaIfMissing(): IO[Int] = {
    Try(sql"SELECT COUNT(*) from drivers".query[Int].unique.transact(xa).unsafeRunSync())
      .fold(
        _ ⇒ {
          sql"CREATE TABLE drivers (id bigint NOT NULL, full_name character varying(255), license_series character varying(15), license_number character varying(15), CONSTRAINT drivers_pkey PRIMARY KEY (id)) WITH (OIDS=FALSE);"
            .update.run.transact(xa)
        },
        n ⇒ IO.pure(n)
      )
  }
}
