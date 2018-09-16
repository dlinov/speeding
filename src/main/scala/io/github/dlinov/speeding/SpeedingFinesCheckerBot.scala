package io.github.dlinov.speeding

import java.net.HttpCookie
import java.util.{Locale, UUID}

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.semigroup._
import cats.instances.string._
import cats.instances.option._
import info.mukel.telegrambot4s.api.{Polling, TelegramBot}
import info.mukel.telegrambot4s.api.declarative.{Callbacks, Commands, Help, ToCommand}
import info.mukel.telegrambot4s.methods.SendMessage
import info.mukel.telegrambot4s.models._
import io.github.dlinov.speeding.dao.Dao
import io.github.dlinov.speeding.dao.Dao.DaoError
import io.github.dlinov.speeding.model.DriverInfo
import io.github.dlinov.speeding.model.parser.ResponseParser
import org.jmotor.i18n.Messages
import scalaj.http._

import scala.concurrent.Future

class SpeedingFinesCheckerBot(override val token: String, dao: Dao)
  extends TelegramBot with Commands with Polling with Callbacks with Help {

  import SpeedingFinesCheckerBot._

  override def helpFooter(): String = Greeting

  private val messages = Messages("messages")
  private val defaultLocale = new Locale("ru") // intentionally left non-implicit

  onCommandWithHelp('forcecheck)("Force check of speeding fines") { implicit msg ⇒
    skipBots {
      val chatId = msg.source
      logger.info(s"Chat $chatId asked to check its fines")
      val botResponse = dao.find(chatId).unsafeRunSync()
        .fold(
          err ⇒ {
            val errorId = UUID.randomUUID()
            logger.error(s"Couldn't check db for chat $chatId [$errorId]: ${err.message}")
            messages.format("errors.internal", errorId)(defaultLocale)
          },
          _.fold {
            logger.warn(s"Couldn't find chat $chatId")
            messages.format("errors.chatNotFound")(defaultLocale)
          } { d ⇒
          implicit val locale: Locale = d.locale
            checkDriverFines(retrieveCookies, d, onlyNew = false)
              .fold {
                logger.info(s"No fines were found for chat $chatId")
                messages.format("noFinesFound")
              } { finesResponse ⇒ // all fines go here
                logger.info(s"Something was found for $chatId: '$finesResponse'")
                finesResponse
              }
          })
      reply(botResponse)
    }
  }

  onCommandWithHelp('lang)("Изменить язык бота") { implicit msg ⇒
    skipBots {
      val chatId = msg.source
      logger.info(s"Chat $chatId asked to change bot language")

      dao.find(chatId).unsafeRunSync()
        .fold(
          err ⇒ {
            val errorId = UUID.randomUUID()
            logger.error(s"Couldn't check db for chat $chatId [$errorId]: ${err.message}")
            reply(messages.format("errors.internal", errorId)(defaultLocale))
          },
          _.fold {
            logger.warn(s"Couldn't find chat $chatId")
            reply(messages.format("errors.chatNotFound")(defaultLocale))
          } { d ⇒
            val supportedLanguages = Seq(
              "\uD83C\uDDE7\uD83C\uDDFE" → "by",
              "\uD83C\uDDF7\uD83C\uDDFA" → "ru")
            val inlineBtns = InlineKeyboardMarkup(Seq(
              supportedLanguages
                .map(lang ⇒ InlineKeyboardButton(lang._1, callbackData = Some(lang._2)))))
            val currentLanguage = supportedLanguages
              .find(_._2 == d.lang)
              .map(_._1)
              .getOrElse(defaultLocale.getLanguage)
            reply(text = messages.format("lang.current", currentLanguage)(d.locale), replyMarkup = Some(inlineBtns))
          })
    }
  }

  onCallbackQuery { implicit callbackQuery ⇒
    logger.debug(s"cbq: $callbackQuery}")
    // You must acknowledge callback queries, even if there's no response.
    // e.g. just ackCallback()
    val userId = callbackQuery.from.id
    callbackQuery.data.foreach { lang ⇒
      logger.debug(s"data: $lang")
      val desiredLocale = new Locale(lang)
      dao.updateLang(userId, lang)
        .unsafeRunSync()
        .fold(
          err ⇒ {
            val errId = UUID.randomUUID()
            logger.warn(s"Failed to update user $userId language [$errId]: ${err.message}")
            ackCallback(text = Some(messages.format("errors.internal", errId)(desiredLocale)))
          },
          _ ⇒ {
            ackCallback(text = Some(messages.format("lang.changed")(desiredLocale)))
          }
        )
    }
  }

  onCommandWithHelp('stop, 'delete, 'remove)("Удалить данные техпаспорта и отменить проверку") { implicit msg ⇒
    skipBots {
      val chatId = msg.source
      logger.info(s"Chat $chatId asked to remove driver data")
      val botResponse = (for {
        driver ← EitherT[IO, DaoError, Option[DriverInfo]](dao.find(chatId))
        _ ← EitherT[IO, DaoError, Unit](dao.deleteDriverInfo(chatId))
      } yield {
        messages.format("data.removed")(driver.map(_.locale).getOrElse(defaultLocale))
      }).value.unsafeRunSync()
        .fold(
          err ⇒ {
            val errorId = UUID.randomUUID()
            logger.error(s"Couldn't check db for chat $chatId [$errorId]: ${err.message}")
            messages.format("errors.internal", errorId)(defaultLocale)
          },
          identity)
      reply(botResponse)
    }
  }

  onMessage { implicit msg ⇒
    val msgText = msg.text
    val chatId = msg.source
    if (msgText.forall(!_.startsWith(ToCommand.CommandPrefix))) {
      skipBots {
        val botResponse = msgText match {
          case Some(InputRegex(lastName, firstName, middleName, licenseSeries, licenseNumber)) ⇒
            val driverInfo = DriverInfo(
              id = chatId,
              fullName = s"$lastName $firstName $middleName".toUpperCase,
              licenseSeries = licenseSeries.toUpperCase,
              licenseNumber = licenseNumber.toUpperCase,
              lang = defaultLocale.getLanguage)
            val dbResult = dao.update(chatId, driverInfo).unsafeRunSync()
            dbResult.fold(
              err ⇒ {
                val errorId = UUID.randomUUID()
                logger.warn(s"Couldn't update data for $chatId [$errorId]: ${err.message}")
                messages.format("errors.save.internal", errorId)(defaultLocale)
              },
              _ ⇒ {
                logger.info(s"Chat $chatId updated its data to $driverInfo")
                messages.format("data.saved")(defaultLocale)
              })

          case _ ⇒
            logger.warn(s"'$msgText' from $chatId didn't match input regex")
            messages.format("errors.save.badrequest")(defaultLocale)
        }
        reply(botResponse)
      }
    }
  }

  def performCheckForAllDrivers(): Unit = {
    val allDrivers = dao.findAll.unsafeRunSync()
    val cookies = retrieveCookies
    allDrivers.foreach(_.foreach { driver ⇒
      checkDriverFines(cookies, driver, onlyNew = true)
        .foreach(sendMessageTo(driver.id, _))
    })
  }

  private def sendMessageTo(chatId: Long, text: String): Future[Message] = {
    request(SendMessage(chatId, text))
  }

  private def checkDriverFines(
                                cookies: IndexedSeq[HttpCookie],
                                driverInfo: DriverInfo,
                                onlyNew: Boolean): Option[String] = {
    // TODO: wrap everything to IO
    val resp = speedingReq(driverInfo, cookies).asString
    (for {
      activeFines ← ResponseParser.parse(driverInfo.id, resp.body)
      activeFineIds = activeFines.map(_.id).toSet
      existingUnpaidFines ← dao.findUnpaidDriverFines(driverInfo.id).unsafeRunSync()
      (unpaidFines, paidFines) = existingUnpaidFines.partition(f ⇒ activeFineIds.contains(f.id))
      unpaidFineIds = unpaidFines.map(_.id).toSet
      newFines = activeFines.filter(f ⇒ !unpaidFineIds.contains(f.id))
      _ ← dao.setFinesPaid(paidFines.map(_.id)).unsafeRunSync()
      _ ← dao.createFines(newFines).unsafeRunSync()
    } yield {
      val paidPart = paidFines
        .headOption
        .map { _ ⇒
          val paid = paidFines.map(_.toHumanString).mkString("\n", "\n", "\n")
          "Вы оплатили следующие штрафы:" + paid
        }
      val fs = if (onlyNew) newFines else activeFines
      val activePart = fs
        .headOption
        .map { _ ⇒
          val active = fs.map(_.toHumanString).mkString("\n", "\n", "\n")
          "У вас есть неоплаченные штрафы:" + active
        }
      paidPart |+| activePart
    }).toOption.flatten
  }

  private def skipBots(action: ⇒ Unit)(implicit msg: Message): Unit = {
    msg.from.foreach(user ⇒ {
      if (!user.isBot) {
        action
      } else {
        logger.info(s"User ${getUserId(user)} is bot, skipping his request")
      }
    })
  }

}

object SpeedingFinesCheckerBot {
  private final val Greeting =
    """
      |Этот бот будет проверять информацию о превышении вами скорости.
      |Чтобы добавить ваши данные отправьте сообщение следующего вида:
      |ФАМИЛИЯ ИМЯ ОТЧЕСТВО СЕРИЯ_ТЕХПАСПОРТА НОМЕР_ТЕХПАСПОРТА""".stripMargin

  private final val InputRegex =
    """^([А-Я]{1,32})\s*([А-Я]{1,32})\s*([А-Я]{1,32})\s*([А-Я]{3})\s*([0-9]{7})\s*$""".r

  private final val SessionCookieReq = Http("http://mvd.gov.by/main.aspx?guid=15791")
  private final val SpeedingBaseCheckReq = Http("http://mvd.gov.by/Ajax.asmx/GetExt")
    .method("POST")
    .headers(
      "Host" → "mvd.gov.by",
      "Origin" → "http://mvd.gov.by",
      "Referer" → SessionCookieReq.url,
      "Content-Type" → "application/json; charset=UTF-8")

  private def speedingReq(driverInfo: DriverInfo, cookies: Seq[HttpCookie]): HttpRequest = {
    val fn = driverInfo.fullName
    val ls = driverInfo.licenseSeries
    val ln = driverInfo.licenseNumber
    val json = s"""{"GuidControl": 2091, "Param1": "$fn", "Param2": "$ls", "Param3": "$ln"}"""
    SpeedingBaseCheckReq
      .cookies(cookies)
      .postData(json)
  }

  private def retrieveCookies: IndexedSeq[HttpCookie] = {
    SessionCookieReq.asString.cookies
  }

  private def getUserId(user: User): String = user.username.map("@" + _).getOrElse(user.id.toString)
}
