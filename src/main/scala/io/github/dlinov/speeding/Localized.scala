package io.github.dlinov.speeding

import java.util.Locale

import cats.effect.IO
import com.bot4s.telegram.models.Message
import io.github.dlinov.speeding.dao.DaoProvider
import org.jmotor.i18n.Messages

trait Localized { self: DaoProvider ⇒
  val messages = Messages("messages")

  def getUserLocale(implicit msg: Message): IO[Locale] = {
    val tgLang = msg.from
      .flatMap(_.languageCode)
      .flatMap(_.split("-").headOption.map(new Locale(_)))
      .getOrElse(Localized.DefaultLocale)
    dao
    .findUser(msg.source)
    .map(_.toOption.flatMap(_.map(_.locale)).getOrElse(tgLang))
  }
}

object Localized {
  private val DefaultLocale = new Locale("ru") // intentionally left non-implicit
}
