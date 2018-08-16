package io.github.dlinov.speeding

import java.net.HttpCookie

import info.mukel.telegrambot4s.api.declarative.{Commands, Help}
import info.mukel.telegrambot4s.api.{Extractors, Polling, TelegramBot}
import info.mukel.telegrambot4s.methods.SendMessage
import info.mukel.telegrambot4s.models._
import io.github.dlinov.speeding.dao.Dao
import io.github.dlinov.speeding.model.DriverInfo
import scalaj.http._

import scala.concurrent.Future
import scala.concurrent.duration._

class SpeedingFinesCheckerBot(override val token: String, dao: Dao)
  extends TelegramBot with Commands with Polling with Help {

  import SpeedingFinesCheckerBot._

  system.scheduler.schedule(20.seconds, 300.seconds, () ⇒ {
    val allDrivers = dao.findAll.unsafeRunSync()
    val cookies = MvdPageReq.asString.cookies
    logger.info(memoryInfo)
    allDrivers.foreach(_.map(t ⇒ {
      val (msgSource, driverInfo) = t
      val resp = speedingReq(driverInfo, cookies).asString
      if (resp.body.contains("<html>")) {
        logger.error("Some error: " + resp.body)
        Future.successful(())
      } else {
        val text = resp.body
          .replace("\"\\u003ch2\\u003e", "")
          .replace("\\u003c/h2\\u003e\"", "")
        request(
          SendMessage(
            msgSource,
            text
          )
        )
      }
    }))
  })

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
      if (!user.isBot) {
        val parts = Extractors.textTokens(msg).getOrElse(Seq.empty)
        if (parts.length != 5) {
          reply("Error: wrong format. Your message should contain 5 words")
          reply(Greeting)
        } else {
          val driverInfo = DriverInfo(
            fullName = (parts.head.trim + " " + parts(1).trim + " " + parts(2).trim).toUpperCase,
            licenseSeries = parts(3).toUpperCase,
            licenseNumber = parts(4).toUpperCase)
          dao.update(msg.source, driverInfo).unsafeRunSync()
        }
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

  private final val MvdPageReq = Http("http://mvd.gov.by/main.aspx?guid=15791")
  private final val SpeedingBaseCheckReq = Http("http://mvd.gov.by/Ajax.asmx/GetExt")
    .method("POST")
    .headers(
      "Host" → "mvd.gov.by",
      "Origin" → "http://mvd.gov.by",
      "Referer" → MvdPageReq.url,
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

  private def getUserId(user: User): String = user.username.map("@" + _).getOrElse(user.id.toString)

  private def memoryInfo: String = {
    val rt = Runtime.getRuntime
    val mb = 1 << 20
    s"free: ${rt.freeMemory() / mb}\ttotal: ${rt.totalMemory() / mb}\tmax: ${rt.maxMemory() / mb}"
  }
}
