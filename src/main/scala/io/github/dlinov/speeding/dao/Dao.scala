package io.github.dlinov.speeding.dao

import cats.effect.IO
import io.github.dlinov.speeding.dao.Dao.{DaoError, GenericDaoError}
import io.github.dlinov.speeding.model.{BotUser, DriverInfo, Fine}

trait Dao {
  type DaoEither[+T] = Either[DaoError, _ <: T]
  type DaoResp[+T] = IO[DaoEither[T]]

  def error(message: String): DaoError = new GenericDaoError(message)

  def createSchemaIfMissing(): IO[Int]

  def updateDriver(userId: Long, driverInfo: DriverInfo): DaoResp[DriverInfo]

  def findDriver(id: Long): DaoResp[Option[DriverInfo]]

  def findUser(id: Long): DaoResp[Option[BotUser]]

  def findAll: DaoResp[Seq[DriverInfo]]

  def updateUser(userId: Long, lang: String): DaoResp[BotUser]

  def createFine(fine: Fine): DaoResp[Fine]

  def createFines(fines: Seq[Fine]): DaoResp[Seq[Fine]]

  def findFine(fineId: Long): DaoResp[Option[Fine]]

  def findAllDriverFines(driverId: Long): DaoResp[Seq[Fine]]

  def findUnpaidDriverFines(driverId: Long): DaoResp[Seq[Fine]]

  def setFinesPaid(fineIds: Seq[Long]): DaoResp[Int]

  def deleteUserData(userId: Long): DaoResp[Unit]
}

object Dao {
  sealed abstract class DaoError(val message: String)

  class GenericDaoError(override val message: String) extends DaoError(message)
}
