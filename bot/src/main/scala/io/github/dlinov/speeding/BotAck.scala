package io.github.dlinov.speeding

final case class BotAck(
    text: Option[String] = None,
    showAlert: Option[Boolean] = None,
    url: Option[String] = None,
    cacheTime: Option[Int] = None
)

object BotAck {
  implicit class PlainBotAck(val text: String) extends AnyVal {
    def asPlainAck: BotAck = BotAck(text = Some(text))
  }
}
