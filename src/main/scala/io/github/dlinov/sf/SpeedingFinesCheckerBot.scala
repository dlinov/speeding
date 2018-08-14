package io.github.dlinov.sf

import java.net.HttpCookie

import info.mukel.telegrambot4s.api.declarative.{Commands, Help}
import info.mukel.telegrambot4s.api.{Extractors, Polling, TelegramBot}
import info.mukel.telegrambot4s.models._
import scalaj.http._

import scala.collection.mutable
import scala.concurrent.duration._

class SpeedingFinesCheckerBot(override val token: String)
  extends TelegramBot with Commands with Polling with Help {

  import SpeedingFinesCheckerBot._

  type UserId = Int
  private val drivers = {
    system.scheduler.schedule(Duration.Zero, 30.seconds, () ⇒ logger.info(memoryInfo))
    mutable.Map[UserId, DriverInfo]()
  }

  onCommand('start) { implicit msg ⇒
    msg.from.foreach(user ⇒ {
      if (!user.isBot) {
        reply(Greeting)
      } else {
        logger.info(s"User ${getUserId(user)} is bot, skipping his request")
      }
    })
  }

  onMessage { implicit msg ⇒
    msg.from.foreach(user ⇒ {
      val parts = Extractors.textTokens(msg).getOrElse(Seq.empty)
      if (parts.length != 5) {
        reply("Error: wrong format. Your message should contain 5 words")
        reply(Greeting)
      } else {
        val userId = user.id
        val driverInfo = DriverInfo(
          firstName = parts.head.toUpperCase,
          middleName = parts(1).toUpperCase,
          lastName = parts(2).toUpperCase,
          licenseSeries = parts(3).toUpperCase,
          licenseNumber = parts(4).toUpperCase)
        drivers.update(userId, driverInfo)
        system.scheduler.schedule(Duration.Zero, 300.seconds, () ⇒ {
          drivers.get(userId).foreach(driverInfo ⇒ {
            val cookies = MvdPageReq.asString.cookies
            val resp = speedingReq(driverInfo, cookies).asString
            if (resp.body.contains("<html>")) {
              logger.error("Some error: " + resp.body)
            } else {
              reply(resp.body
                .replace("\"\\u003ch2\\u003e", "")
                .replace("\\u003c/h2\\u003e\"", ""))
            }
          })
        })
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

  private final val MvdPageReq = Http("http://mvd.gov.by/main.aspx?guid=15791")

  private def speedingReq(driverInfo: DriverInfo, cookies: Seq[HttpCookie]): HttpRequest = {
    val fn = driverInfo.firstName
    val mn = driverInfo.middleName
    val ln = driverInfo.lastName
    val dls = driverInfo.licenseSeries
    val dln = driverInfo.licenseNumber
    val json = s"""{GuidControl: 2091, Param1: "$fn $mn $ln", Param2: "$dls", Param3: "$dln"}"""
    Http("http://mvd.gov.by/Ajax.asmx/GetExt")
      .method("POST")
      .cookies(cookies)
      .headers(
        "Host" → "mvd.gov.by",
        "Origin" → "http://mvd.gov.by",
        "Referer" → MvdPageReq.url,
        "Content-Type" → "application/json; charset=UTF-8")
      .postData(json)
  }

  private def getUserId(user: User): String = user.username.map("@" + _).getOrElse(user.id.toString)

  private def memoryInfo: String = {
    val rt = Runtime.getRuntime
    val mb = 1 << 20
    s"free: ${rt.freeMemory() / mb}\ttotal: ${rt.totalMemory() / mb}\tmax: ${rt.maxMemory() / mb}"
  }

  case class DriverInfo(
      firstName: String,
      middleName: String,
      lastName: String,
      licenseSeries: String,
      licenseNumber: String)
}
