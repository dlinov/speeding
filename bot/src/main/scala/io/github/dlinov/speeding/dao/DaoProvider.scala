package io.github.dlinov.speeding.dao

trait DaoProvider[F[_]] {
  def dao: Dao[F]
}
