package io.github.dlinov.sf.dao

import cats._
import cats.effect._
import cats.implicits._
import doobie._
import doobie.implicits._
import io.github.dlinov.sf.dao.Dao.DaoError
import io.github.dlinov.sf.model.DriverInfo
import org.slf4j.LoggerFactory

class PostgresDao(dbUri: String, user: String, password: String) extends Dao {

  private val logger = LoggerFactory.getLogger(classOf[PostgresDao])

  private val xa = {
    Transactor.fromDriverManager[IO](
      "org.postgresql.Driver", // driver classname
      dbUri, // connect URL (driver-specific)
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
      logger.info(s"Update result: $updResult")
      Right(driverInfo): Either[DaoError, DriverInfo]
    }).transact(xa)
  }

  override def findAll: DaoResp[Seq[(Long, DriverInfo)]] = {
    (for {
      all ← sql"SELECT id, full_name, license_series, license_number FROM drivers"
        .query[(Long, String, String, String)]
        .to[List]
    } yield {
      logger.info(s"Find all result: $all")
      Right(all.map(t ⇒ t._1 → DriverInfo(fullName = t._2, licenseSeries = t._3, licenseNumber = t._4)))
    }).transact(xa)
  }
}
