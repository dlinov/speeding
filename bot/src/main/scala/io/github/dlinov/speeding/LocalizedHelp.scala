package io.github.dlinov.speeding

import java.util.Locale

import cats.effect.Sync
import com.bot4s.telegram.api.declarative._
import com.bot4s.telegram.Implicits._
import com.bot4s.telegram.models.Message

import scala.collection.mutable.ArrayBuffer

trait LocalizedHelp[F[_]] { self: Commands[F] with Localized[F] ⇒

  private case class CommandDescription(
      variants: Seq[String],
      description: String,
      category: Option[String] = None,
      helpHandler: Option[Action[F, Message]] = None
  )

  private val commandsDescription = ArrayBuffer[CommandDescription]()

  def onCommandWithHelp(filters: String*)(
      description: String,
      category: Option[String] = None,
      helpHandler: Option[Action[F, Message]] = None
  )(action: Action[F, Message]): Unit = {
    val filter = HintedCommandFilterMagnet(filters: _*)
    onCommand(filter)(action)
    commandsDescription += CommandDescription(filters, description, category, helpHandler)
  }

  def helpHeader()(implicit locale: Locale): String = messages.format("help.header")

  def helpFooter()(implicit locale: Locale): String = messages.format("greeting")

  def helpBody()(implicit locale: Locale): String = {
    val (orphans, categorized) = commandsDescription.partition(_.category.isEmpty)
    // Non-categorized commands first.
    val cmdsByCategories = (None, orphans) :: categorized.groupBy(_.category).toList

    val allCommands = cmdsByCategories
      .map {
        case (category, group) ⇒
          category.map(_.bold + "\n").getOrElse("") +
            group
              .map { cmd ⇒
                val variants = cmd.variants.map("/" + _).mkString("|")
                variants.mdEscape + " - " + messages.format(cmd.description)
              }
              .mkString("\n")
      }
      .mkString("\n\n")
    allCommands
  }

  def help(implicit msg: Message, locale: Locale): F[Message] =
    replyMd(
      helpHeader() + "\n" +
        helpBody() + "\n" +
        helpFooter() + "\n"
    )

  def helpHelp(implicit msg: Message, locale: Locale): F[Message] =
    replyMd(messages.format("help.help"))

  def sync: Sync[F]

  // TODO: call getUserLocale only once
  onCommandWithHelp("help")(
    "help.description",
    helpHandler =
      Some(msg ⇒ sync.flatMap(getUserLocale(msg))(locale => sync.void(helpHelp(msg, locale))))
  ) { implicit msg =>
    sync.flatMap(getUserLocale) { implicit locale =>
      withArgs {
        case Seq() => sync.void(help(msg, locale))
        case Seq(command) =>
          val target = command.stripPrefix("/").toLowerCase()
          val cmdOpt = commandsDescription.find(_.variants.contains(target))
          cmdOpt match {
            case Some(cmd) =>
              cmd.helpHandler.map(_(msg)).getOrElse(sync.unit)
            case _ =>
              sync.void(replyMd(s"${messages.format("help.unknown")} /$target"))
          }
      }
    }
  }
}
