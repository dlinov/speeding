package io.github.dlinov.speeding.model
import java.time.Instant

final case class Fine(id: Long, time: Instant, isActive: Boolean) {
  def toHumanString = s"№ штрафа: $id; время: $time"
}

object Fine {
  def apply(id: Long, time: Instant): Fine =
    apply(id, time, isActive = true)
}
