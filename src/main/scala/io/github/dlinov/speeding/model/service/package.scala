package io.github.dlinov.speeding.model
import io.github.dlinov.speeding.model.service.Errors.ServiceError

package object service {
  type ServiceResp[T] = Either[ServiceError, T]
}
