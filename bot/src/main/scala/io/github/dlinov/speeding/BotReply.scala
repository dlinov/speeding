package io.github.dlinov.speeding

import com.bot4s.telegram.methods.ParseMode.ParseMode
import com.bot4s.telegram.models.ReplyMarkup

final case class BotReply(
    text: String,
    parseMode: Option[ParseMode] = None,
    disableWebPagePreview: Option[Boolean] = None,
    disableNotification: Option[Boolean] = None,
    replyToMessageId: Option[Int] = None,
    replyMarkup: Option[ReplyMarkup] = None
)

object BotReply {
  implicit class PlainBotReply(val text: String) extends AnyVal {
    def asPlainReply: BotReply = BotReply(text = text)
  }
}
