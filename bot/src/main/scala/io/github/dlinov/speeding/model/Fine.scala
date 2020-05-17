package io.github.dlinov.speeding.model
import java.time.{Instant, ZonedDateTime}

final case class Fine(id: Long, driverId: Long, timestamp: Instant, isActive: Boolean) {
  def toHumanString = s"№ штрафа: $id; время: $time"

  lazy val time: ZonedDateTime = ZonedDateTime.ofInstant(timestamp, Constants.TZ)
}

object Fine {
  def apply(id: Long, driverId: Long, timestamp: Instant): Fine =
    apply(id, driverId, timestamp, isActive = true)
}
