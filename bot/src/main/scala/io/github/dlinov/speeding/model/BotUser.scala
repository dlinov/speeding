package io.github.dlinov.speeding.model

import java.util.Locale

final case class BotUser(id: Long, lang: String) {
  def locale: Locale = Locale.forLanguageTag(lang)
}
