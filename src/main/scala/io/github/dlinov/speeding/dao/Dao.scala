package io.github.dlinov.speeding.dao

import cats.effect.IO
import io.github.dlinov.speeding.dao.Dao.{DaoError, GenericDaoError}
import io.github.dlinov.speeding.model.{DriverInfo, Fine}

trait Dao {
  type DaoResp[+T] = IO[Either[DaoError, _ <: T]]

  def error(message: String): DaoError = new GenericDaoError(message)

  def createSchemaIfMissing(): IO[Int]

  def update(userId: Long, driverInfo: DriverInfo): DaoResp[DriverInfo]

  def updateLang(userId: Long, lang: String): DaoResp[DriverInfo]

  def find(id: Long): DaoResp[Option[DriverInfo]]

  def findAll: DaoResp[Seq[DriverInfo]]

  def createFines(fines: Seq[Fine]): DaoResp[Seq[Fine]]

  def findFine(fineId: Long): DaoResp[Option[Fine]]

  def findAllDriverFines(driverId: Long): DaoResp[Seq[Fine]]

  def findUnpaidDriverFines(driverId: Long): DaoResp[Seq[Fine]]

  def setFinesPaid(fineIds: Seq[Long]): DaoResp[Int]

  def deleteDriverInfo(userId: Long): DaoResp[Unit]
}

object Dao {
  sealed abstract class DaoError(val message: String)

  class GenericDaoError(override val message: String) extends DaoError(message)
}