package io.github.dlinov.sf.dao

import cats.effect.IO
import io.github.dlinov.sf.dao.Dao.DaoError
import io.github.dlinov.sf.model.DriverInfo

trait Dao {
  type DaoResp[+T] = IO[Either[DaoError, _ <: T]]

  def update(userId: Long, driverInfo: DriverInfo): DaoResp[DriverInfo]

  def findAll: DaoResp[Seq[(Long, DriverInfo)]]
}

object Dao {
  sealed abstract class DaoError(val message: String)

  class GenericDaoError(override val message: String) extends DaoError(message)
}