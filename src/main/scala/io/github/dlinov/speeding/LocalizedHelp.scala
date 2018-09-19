package io.github.dlinov.speeding

import java.util.Locale

import info.mukel.telegrambot4s.api.declarative.{Action, Commands, ToCommand}
import info.mukel.telegrambot4s.Implicits._
import info.mukel.telegrambot4s.models.Message

import scala.collection.mutable.ArrayBuffer

// rewritten version of info.mukel.telegrambot4s.api.declarative.Help with support of localizing the help
trait LocalizedHelp extends Commands { self: Localized ⇒
  def listAllCommandsInHelp: Boolean = true

  private case class CommandDescription(variants    : Seq[String],
                                        description : String,
                                        category    : Option[String] = None,
                                        helpHandler : Option[Action[Message]] = None)

  private val commandsDescription = ArrayBuffer[CommandDescription]()

  def onCommandWithHelp[T : ToCommand](commands: T*)
                                      (description: String, category: Option[String] = None, helpHandler : Option[Action[Message]] = None)
                                      (action: Action[Message]): Unit = {
    super.onCommand(commands : _*)(action)
    val toCommandImpl = implicitly[ToCommand[T]]
    val variants = commands.map(toCommandImpl.apply)
    commandsDescription += CommandDescription(variants, description, category, helpHandler)
  }

  abstract override def onCommand[T : ToCommand](commands: T*)(action: Action[Message]): Unit = {
    if (listAllCommandsInHelp)
      onCommandWithHelp(commands : _*)("no.description")(action)
    else
      super.onCommand(commands: _*)(action)
  }

  def helpHeader()(implicit locale: Locale): String = messages.format("help.header")

  def helpFooter()(implicit locale: Locale): String = messages.format("greeting")

  def helpBody()(implicit locale: Locale): String = {
    val (orphans, categorized) = commandsDescription.partition(_.category.isEmpty)
    // Non-categorized commands first.
    val cmdsByCategories = (None, orphans) :: categorized.groupBy(_.category).toList

    val allCommands = cmdsByCategories.map {
      case (category, group) =>
        category.map(_.bold + "\n").getOrElse("") +
          group.map {
            cmd =>
              val variants = cmd.variants.map("/" + _).mkString("|")
              variants.md + " - " + messages.format(cmd.description)
          }.mkString("\n")
    }.mkString("\n\n")
    allCommands
  }

  def help(implicit msg: Message, locale: Locale): Unit = {
    replyMd(
      helpHeader() + "\n" +
        helpBody()   + "\n" +
        helpFooter() + "\n"
    )
  }

  def helpHelp(implicit msg: Message, locale: Locale): Unit = {
    replyMd(messages.format("help.help"))
  }

  // TODO: call getUserLocale only once
  onCommandWithHelp('help)("help.description", helpHandler = Some(msg ⇒ helpHelp(msg, getUserLocale(msg).unsafeRunSync()))) {
    implicit msg =>
      implicit val locale: Locale = getUserLocale.unsafeRunSync()
      withArgs {
        case Seq() => help(msg, locale)
        case Seq(command) =>
          val target = command.stripPrefix("/").toLowerCase()
          val cmdOpt = commandsDescription.find(_.variants.contains(target))
          cmdOpt
            .map(_.helpHandler.foreach(_.apply(msg)))
            .orElse(replyMd(s"${messages.format("help.unknown")} /$target"))
      }
  }
}
