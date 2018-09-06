package io.github.dlinov.speeding

import java.net.HttpCookie
import java.util.UUID

import cats.syntax.semigroup._
import cats.instances.string._
import cats.instances.option._
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
            checkDriverFines(retrieveCookies, d, onlyNew = false)
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
              id = chatId,
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
          "You have paid the following fines:" + paid
        }
      val fs = if (onlyNew) newFines else activeFines
      val activePart = fs
        .headOption
        .map { _ ⇒
          val active = fs.map(_.toHumanString).mkString("\n", "\n", "\n")
          "You have the following active fines:" + active
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
