package io.github.dlinov.speeding.model.service

object Errors {
  sealed trait ServiceError
  final case class GenericServiceError(message: String) extends ServiceError
  case object NotFound extends ServiceError
}
