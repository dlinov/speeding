package io.github.dlinov.speeding.model.service

import cats.effect.IO
import cats.syntax.either._
import io.github.dlinov.speeding.dao.Dao
import io.github.dlinov.speeding.model.BotUser
import io.github.dlinov.speeding.model.service.Errors.{GenericServiceError, NotFound}

trait UserService {
  def dao: Dao

  def findUser(userId: Long): IO[ServiceResp[BotUser]] =
    dao
      .findUser(userId)
      .map(
        _.leftMap(err ⇒ GenericServiceError(err.message))
          .flatMap(_.toRight(NotFound)))
}
