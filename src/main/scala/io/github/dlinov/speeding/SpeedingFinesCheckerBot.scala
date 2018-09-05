package io.github.dlinov.speeding

import java.net.HttpCookie
import java.util.UUID

import info.mukel.telegrambot4s.api.{Polling, TelegramBot}
import info.mukel.telegrambot4s.api.declarative.{Commands, Help, ToCommand}
import info.mukel.telegrambot4s.methods.SendMessage
import info.mukel.telegrambot4s.models._
import io.github.dlinov.speeding.dao.Dao
import io.github.dlinov.speeding.model.DriverInfo
import io.github.dlinov.speeding.model.parser.ResponseParser
import scalaj.http._

import scala.concurrent.Future

class SpeedingFinesCheckerBot(override val token: String, dao: Dao)
  extends TelegramBot with Commands with Polling with Help {

  import SpeedingFinesCheckerBot._

  override def helpFooter(): String = Greeting

  onCommandWithHelp('forcecheck)("Force check of speeding fines") { implicit msg ⇒
    skipBots {
      val chatId = msg.source
      logger.info(s"Chat $chatId asked to check its fines")
      val botResponse = dao.find(chatId).unsafeRunSync()
        .fold(
          err ⇒ {
            logger.error(s"Couldn't check db for chat $chatId: ${err.message}")
            "There was an error processing your request"
          },
          _.fold {
            logger.warn(s"Couldn't find chat $chatId")
            "There is no saved driver info for you"
          } { d ⇒
            checkDriverFines(retrieveCookies, d)
              .fold {
                logger.info(s"No fines were found for chat $chatId")
                "Congratulations! No camera speeding records were found for you."
              } { finesResponse ⇒ // all fines go here
                logger.info(s"Something was found for $chatId: '$finesResponse'")
                finesResponse
              }
          })
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
              fullName = s"$lastName $firstName $middleName".toUpperCase,
              licenseSeries = licenseSeries.toUpperCase,
              licenseNumber = licenseNumber.toUpperCase)
            val dbResult = dao.update(chatId, driverInfo).unsafeRunSync()
            dbResult.fold(
              err ⇒ {
                val errorId = UUID.randomUUID()
                logger.warn(s"Couldn't update data for $chatId [$errorId]: ${err.message}")
                s"Your data was not saved due to internal error, please try later. Use this error id: $errorId"
              },
              _ ⇒ {
                logger.info(s"Chat $chatId updated its data to $driverInfo")
                "Your data was saved. You will receive updates when any fine is found"
              })

          case _ ⇒
            logger.warn(s"'$msgText' from $chatId didn't match input regex")
            "Error: wrong format. Your message must contain exactly 5 words. Use /help to get more info"
        }
        reply(botResponse)
      }
    }
  }

  def performCheckForAllDrivers(): Unit = {
    val allDrivers = dao.findAll.unsafeRunSync()
    val cookies = retrieveCookies
    allDrivers.foreach(_.foreach {
      case (chatId, driverInfo) ⇒
        checkDriverFines(cookies, driverInfo)
          .foreach(sendMessageTo(chatId, _))
    })
  }

  private def sendMessageTo(chatId: Long, text: String): Future[Message] = {
    request(SendMessage(chatId, text))
  }

  private def checkDriverFines(
                                cookies: IndexedSeq[HttpCookie],
                                driverInfo: DriverInfo): Option[String] = {
    val resp = speedingReq(driverInfo, cookies).asString
    ResponseParser.parse(resp.body)
      .fold(
        err ⇒ Some(err.message),
        fines ⇒ {
          // TODO: save fetched fines, don't notify about the same fine twice, mark paid fines
          fines
            .headOption
            .map(_ ⇒ "You have the following fines: " + fines.map(_.toHumanString).mkString("\n"))
        }
      )
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
      |This bot is intended to periodically check if you exceeded speeding limits.
      |Please send data in the following format:
      |LASTNAME FIRSTNAME MIDDLENAME CAR_ID_SERIES CAR_ID_NUMBER""".stripMargin

  private final val InputRegex =
    """^([А-Яа-я]{1,32})\s*([А-Яа-я]{1,32})\s*([А-Яа-я]{1,32})\s*([А-Яа-я]{3})\s*([0-9]{7})\s*$""".r

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
