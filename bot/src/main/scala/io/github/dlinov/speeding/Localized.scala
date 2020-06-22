package io.github.dlinov.speeding

import java.util.Locale

import cats.Functor
import com.bot4s.telegram.models.Message
import io.github.dlinov.speeding.dao.DaoProvider
import org.jmotor.i18n.Messages

trait Localized[F[_]] { self: DaoProvider[F] ⇒
  val messages: Messages = Messages("messages")

  implicit val fnctr: Functor[F]

  def getUserLocale(implicit msg: Message): F[Locale] = {
    val tgLang = msg.from
      .flatMap(_.languageCode)
      .flatMap(_.split("-").headOption.map(new Locale(_)))
      .getOrElse(Localized.DefaultLocale)
    fnctr.map {
      val x = dao.findUser(msg.source)
      x
    } {
      _.toOption
        .flatMap(_.map(_.locale))
        .getOrElse(tgLang)
    }
  }
}

object Localized {
  private val DefaultLocale = new Locale("ru") // intentionally left non-implicit
}
