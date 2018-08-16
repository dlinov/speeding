package io.github.dlinov.speeding

import java.net.HttpCookie

import info.mukel.telegrambot4s.api.declarative.{Commands, Help, ToCommand}
import info.mukel.telegrambot4s.api.{Extractors, Polling, TelegramBot}
import info.mukel.telegrambot4s.methods.SendMessage
import info.mukel.telegrambot4s.models._
import io.github.dlinov.speeding.dao.Dao
import io.github.dlinov.speeding.model.DriverInfo
import scalaj.http._

import scala.concurrent.Future

class SpeedingFinesCheckerBot(override val token: String, dao: Dao)
  extends TelegramBot with Commands with Polling with Help {

  import SpeedingFinesCheckerBot._

  override def helpBody(): String = Greeting

  onCommandWithHelp('forcecheck)("Force check of speeding fines") { implicit msg ⇒
    skipBots {
      dao.find(msg.source).unsafeRunSync()
        .fold(
          _ ⇒ reply("There was an error processing your request"),
          _.fold {
            reply("There is no saved driver info for you")
          } { d ⇒
            checkDriverFines(retrieveCookies, d)
              .fold {
                reply("Congratulations! No camera speeding records were found for you.")
              } {
                reply(_) // all fines go here
              }
          })
    }
  }

  onMessage { implicit msg ⇒
    if (msg.text.forall(!_.startsWith(ToCommand.CommandPrefix))) {
      skipBots {
        val parts = Extractors.textTokens(msg).getOrElse(Seq.empty)
        parts match {
          case firstName :: middleName :: lastName :: licenseSeries :: licenseNumber :: Nil ⇒
            val driverInfo = DriverInfo(
              fullName = (firstName.trim + " " + middleName.trim + " " + lastName.trim).toUpperCase,
              licenseSeries = licenseSeries.toUpperCase,
              licenseNumber = licenseNumber.toUpperCase)
            dao.update(msg.source, driverInfo).unsafeRunSync()
            reply("Your data was saved. You will receive updates when any fine is found")
          case _ ⇒
            reply("Error: wrong format. Your message must contain 5 words. Use /help to get more info")
        }
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
    if (resp.body.contains("<html>")) {
      logger.warn("Some error: " + resp.body)
      None
    } else {
      if (resp.body.contains("информация не найдена")) {
        None
      } else {
        val text = resp.body
          .replace("\"\\u003ch2\\u003e", "")
          .replace("\\u003c/h2\\u003e\"", "")
        Some(text)
      }
    }
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
      |FIRSTNAME MIDDLENAME LASTNAME CAR_ID_SERIES CAR_ID_NUMBER""".stripMargin

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
