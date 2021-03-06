package io.github.dlinov.speeding

import java.net.HttpCookie
import java.nio.file.{Files, Paths}
import java.util.{Locale, UUID}

import cats.data.EitherT
import cats.{Applicative, Functor}
import cats.effect.IO
import cats.instances.either._
import cats.instances.list._
import cats.instances.option._
import cats.instances.string._
import cats.syntax.either._
import cats.syntax.functor._
import cats.syntax.semigroup._
import cats.syntax.traverse._
import com.bot4s.telegram.api.{Polling, RequestHandler, TelegramApiException, TelegramBot}
import com.bot4s.telegram.api.declarative.{Callbacks, Commands}
import com.bot4s.telegram.clients.SttpClient
import com.bot4s.telegram.methods.{GetFile, SendMessage}
import com.bot4s.telegram.models._
import com.softwaremill.sttp.SttpBackend
import io.github.dlinov.speeding.dao.{Dao, DaoProvider}
import io.github.dlinov.speeding.model.DriverInfo
import io.github.dlinov.speeding.model.parser.ResponseParser
import net.sourceforge.tess4j.Tesseract
import scalaj.http._

import scala.concurrent.Future
import scala.util.Try

class SpeedingFinesCheckerBot(val token: String, override val dao: Dao, tessDataPath: String)(
    implicit backend: SttpBackend[Future, Nothing])
    extends TelegramBot
    with Commands
    with Polling
    with Callbacks
    with DaoProvider
    with Localized
    with LocalizedHelp {

  import SpeedingFinesCheckerBot._
  override val client: RequestHandler = new SttpClient(token)

  type EitherS[A] = Either[String, A]
  type IOEitherS[A] = IO[EitherS[A]]
  type Rs = (String, Option[String])
  implicit val applicative: Applicative[IOEitherS] = Applicative[IO].compose(Applicative[EitherS])

  private val ioEitherFunctor = Functor[IO].compose(Functor[dao.DaoEither])
  private val ioEitherListFunctor = ioEitherFunctor.compose(Functor[List])
  private val tess: Tesseract = {
    val t = new Tesseract()
    t.setDatapath(tessDataPath)
    t.setLanguage("rus+bel") // tesseract 4 works well with rus only
    t
  }

  onCommandWithHelp("forcecheck")("bot.description.forcecheck") { implicit msg ⇒
    skipBots {
      val chatId = msg.source
      logger.info(s"Chat $chatId asked to check its fines")
      (for {
        explicitUserLocale ← getUserLocale
        driverResp ← dao.findDriver(chatId)
      } yield {
        implicit val userLocale: Locale = explicitUserLocale
        val botResponse = driverResp
          .fold(
            err ⇒ {
              val errorId = UUID.randomUUID()
              logger.error(s"Couldn't check db for chat $chatId [$errorId]: ${err.message}")
              messages.format("errors.internal", errorId)
            },
            _.fold {
              logger.warn(s"Couldn't find chat $chatId")
              messages.format("errors.chatNotFound")
            } {
              performCheckForSingleDriver(chatId, _)
            }
          )
        reply(botResponse)
      }).unsafeRunSync()
    }
  }

  onCommandWithHelp("lang")("bot.description.updLang") { implicit msg ⇒
    skipBots {
      val chatId = msg.source
      logger.info(s"Chat $chatId asked to change bot language")
      (for {
        explicitUserLocale ← getUserLocale
        userResp ← dao.findUser(chatId)
      } yield {
        implicit val userLocale: Locale = explicitUserLocale
        userResp
          .fold(
            err ⇒ {
              val errorId = UUID.randomUUID()
              logger.error(s"Couldn't check db for chat $chatId [$errorId]: ${err.message}")
              reply(messages.format("errors.internal", errorId))
            },
            _.fold {
              logger.warn(s"Couldn't find chat $chatId")
              reply(messages.format("errors.chatNotFound"))
            } { u ⇒
              val supportedLanguages = Seq(
                "\uD83C\uDDE7\uD83C\uDDFE" → "by",
                "\uD83C\uDDF7\uD83C\uDDFA" → "ru",
                "\uD83C\uDDEC\uD83C\uDDE7" → "en")
              val inlineBtns = InlineKeyboardMarkup(Seq(supportedLanguages
                .map(lang ⇒ InlineKeyboardButton(lang._1, callbackData = Some(lang._2)))))
              val currentLanguage = supportedLanguages
                .find(_._2 == u.lang)
                .map(_._1)
                .getOrElse(userLocale.getLanguage)
              reply(
                text = messages.format("lang.current", currentLanguage)(u.locale),
                replyMarkup = Some(inlineBtns))
            }
          )
      }).unsafeRunSync()
    }
  }

  onCommandWithHelp("profile")("bot.description.profile") { implicit msg ⇒
    skipBots {
      val chatId = msg.source
      logger.info(s"Chat $chatId asked to show profile data")
      (for {
        explicitUserLocale ← getUserLocale
        profileResp ← dao.findDriver(chatId)
      } yield {
        implicit val userLocale: Locale = explicitUserLocale
        profileResp
          .fold(
            err ⇒ {
              val errorId = UUID.randomUUID()
              logger.error(s"Couldn't check db for chat $chatId [$errorId]: ${err.message}")
              reply(messages.format("errors.internal", errorId))
            },
            _.fold {
              logger.warn(s"Couldn't find chat $chatId")
              reply(messages.format("errors.chatNotFound"))
            } { u ⇒
              reply(s"${u.fullName}\n${u.licenseSeries} ${u.licenseNumber}")
            }
          )
      }).unsafeRunSync()
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
      (for {
        resp ← dao.updateUser(userId, lang)
      } yield {
        resp
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
      }).unsafeRunSync()
    }
  }

  onCommandWithHelp("stop", "delete", "remove")("bot.description.removeData") { implicit msg ⇒
    skipBots {
      val chatId = msg.source
      logger.info(s"Chat $chatId asked to remove driver data")
      (for {
        explicitUserLocale ← getUserLocale
        deleteInfoResp ← dao.deleteUserData(chatId)
      } yield {
        implicit val userLocale: Locale = explicitUserLocale
        val botResponse = deleteInfoResp
          .fold(
            err ⇒ {
              val errorId = UUID.randomUUID()
              logger.error(s"Couldn't clear user $chatId data [$errorId]: ${err.message}")
              messages.format("errors.internal", errorId)
            },
            _ ⇒ messages.format("data.removed")
          )
        reply(botResponse)
      }).unsafeRunSync()
    }
  }

  onMessage { implicit msg ⇒
    val msgText = msg.text
    val chatId = msg.source
    if (msgText.forall(!_.startsWith("/"))) {
      skipBots {
        val (updDataResp, maybeFinesResp) = (for {
          locale ← EitherT.liftF(getUserLocale)
          driverInfo ← msg.photo.fold {
            EitherT(IO(parseText(msgText, chatId)(locale)))
          } {
            parsePhotos(_, chatId)(locale)
          }
          replies ← EitherT {
            dao
              .updateDriver(chatId, driverInfo)
              .map(_.fold[Either[Rs, Rs]](
                err ⇒ {
                  val errorId = UUID.randomUUID()
                  logger.warn(s"Couldn't update data for $chatId [$errorId]: ${err.message}")
                  Either.left[Rs, Rs](
                    messages.format("errors.save.internal", errorId)(locale) → None)
                },
                _ ⇒ {
                  logger.info(s"Chat $chatId updated its data to $driverInfo")
                  val checkFinesResp = performCheckForSingleDriver(chatId, driverInfo)(locale)
                  Either.right[Rs, Rs](messages.format("data.saved")(locale) → Some(checkFinesResp))
                }
              ))
          }
        } yield {
          replies
        }).value.unsafeRunSync().merge
        reply(updDataResp)
        maybeFinesResp.foreach(reply(_))
      }
    }
  }

  /*_*/
  def performCheckForAllDrivers(): Unit =
    Try(retrieveCookies)
      .map { cookies ⇒
        val allDrivers = ioEitherFunctor.map(dao.findAll)(_.toList)
        val value = ioEitherListFunctor.map(allDrivers) { driver ⇒
          val userId = driver.id
          dao.findUser(userId).map {
            _.leftMap(err ⇒ s"Cannot query user $userId, but driver info exists: ${err.message}")
              .flatMap(_.toRight(s"User $userId was not found, but driver info exists"))
              .map { user ⇒
                checkDriverFines(cookies, driver, onlyNew = true)(user.locale)
                  .map(sendMessageTo(userId, _))
              }
              .map(_.toRight("Message was not sent, see logs"))
              .void
          }
        }
        value
          .flatMap(_.leftMap(_.message).flatTraverse(_.traverse[IOEitherS, Unit](identity)))
          .unsafeRunSync()
      }
  /*_*/

  def performCheckForSingleDriver(chatId: Long, driverInfo: DriverInfo)(
      implicit locale: Locale): String =
    Try(retrieveCookies)
      .fold(
        err ⇒ {
          val errorId = UUID.randomUUID()
          logger.warn(s"Couldn't retrieve cookies for chat $chatId [$errorId]. $err")
          messages.format("errors.internal", errorId)
        },
        cookies ⇒ {
          checkDriverFines(cookies, driverInfo, onlyNew = false)
            .fold {
              logger.info(s"No fines were found for chat $chatId")
              messages.format("fines.empty")
            } { finesResponse ⇒ // all fines go here
              logger.info(s"Something was found for $chatId: '$finesResponse'")
              finesResponse
            }
        }
      )

  private def sendMessageTo(chatId: Long, text: String): Future[Option[Message]] =
    request(SendMessage(chatId, text))
      .map[Option[Message]](Some(_))
      .recover {
        case TelegramApiException(message, errorCode, maybeCause, _) ⇒
          val preparedErrorMessage =
            s"Couldn't send message '$text' to chat $chatId: $message [$errorCode]"
          maybeCause.fold {
            logger.warn(preparedErrorMessage)
          } { cause ⇒
            logger.warn(s"$preparedErrorMessage. Inner error: ", cause)
          }
          None
      }

  private def checkDriverFines(
      cookies: IndexedSeq[HttpCookie],
      driverInfo: DriverInfo,
      onlyNew: Boolean)(implicit locale: Locale): Option[String] =
    // TODO: wrap everything to IO?
    (for {
      resp ← Try(speedingReq(driverInfo, cookies).asString).toEither
      activeFines ← ResponseParser.parse(driverInfo.id, resp.body)
      activeFineIds = activeFines.map(_.id).toSet
      existingUnpaidFines ← dao.findUnpaidDriverFines(driverInfo.id).unsafeRunSync()
      (unpaidFines, paidFines) = existingUnpaidFines.partition(f ⇒ activeFineIds.contains(f.id))
      unpaidFineIds = unpaidFines.map(_.id).toSet
      newFines = activeFines.filter(f ⇒ !unpaidFineIds.contains(f.id))
      _ ← dao.setFinesPaid(paidFines.map(_.id)).unsafeRunSync()
      _ ← dao.createFines(newFines).unsafeRunSync()
    } yield {
      val paidPart = paidFines.headOption
        .map { _ ⇒
          val paid = paidFines.map(_.toHumanString).mkString("\n", "\n", "\n")
          messages.format("fines.paid") + paid
        }
      val fs = if (onlyNew) newFines else activeFines
      val activePart = fs.headOption
        .map { _ ⇒
          val active = fs.map(_.toHumanString).mkString("\n", "\n", "\n")
          messages.format("fines.unpaid") + active
        }
      paidPart |+| activePart
    }).toOption.flatten

  private def retrieveCookies: IndexedSeq[HttpCookie] =
    SessionCookieReq.asString.cookies

  private def skipBots(action: ⇒ Unit)(implicit msg: Message): Unit =
    msg.from.foreach(user ⇒ {
      if (!user.isBot) {
        action
      } else {
        logger.info(s"User ${getUserId(user)} is bot, skipping his request")
      }
    })

  private def parseText(msgText: Option[String], chatId: Long)(
      implicit locale: Locale): Either[(String, Option[String]), DriverInfo] =
    msgText.map(_.toUpperCase) match {
      case Some(InputRegex(lastName, firstName, middleName, licenseSeries, licenseNumber)) ⇒
        Right(
          DriverInfo(
            id = chatId,
            fullName = s"$lastName $firstName $middleName",
            licenseSeries = licenseSeries,
            licenseNumber = licenseNumber))
      case _ ⇒
        Left {
          logger.warn(s"'$msgText' from $chatId didn't match input regex")
          messages.format("errors.save.badrequest") → None
        }
    }

  private def parsePhotos(photos: Seq[PhotoSize], chatId: Long)(
      implicit locale: Locale): EitherT[IO, (String, Option[String]), DriverInfo] =
    for {
      bestFileId ← EitherT.liftF(IO(photos.maxBy(_.width).fileId))
      fileInfo ← EitherT.liftF(IO.fromFuture(IO(client(GetFile(bestFileId)))))
      filePath ← EitherT(
        IO(
          fileInfo.filePath
            .toRight {
              val errorId = UUID.randomUUID()
              logger.warn(s"Failed to get file $bestFileId from telegram [$errorId]: empty path")
              messages.format("errors.save.internal", errorId) → None
            }))
      fileResponse ← EitherT(IO {
        import cats.syntax.either._
        Try(fileRequest(token, filePath).asBytes).toEither
          .leftMap { exc ⇒
            val errorId = UUID.randomUUID()
            logger.warn(s"Failed to get file $bestFileId from telegram [$errorId]", exc)
            messages.format("errors.save.internal", errorId) → None
          }
      })
      fileBytes ← EitherT(IO {
        if (fileResponse.isSuccess) {
          Right(fileResponse.body)
        } else {
          val errorId = UUID.randomUUID()
          logger.warn(s"Failed to get file $bestFileId from telegram [$errorId]: $fileResponse")
          Left(messages.format("errors.save.internal", errorId) → None)
        }
      })
      ocrData ← EitherT(IO {
        val fileName = Paths.get(filePath).toFile.getName
        val tmpFilePath = Files.createTempFile(s"${chatId}_vid_", s"_$fileName")
        Files.write(tmpFilePath, fileBytes)
        val ocrResult = Try(tess.doOCR(tmpFilePath.toFile))
        Files.delete(tmpFilePath)
        logger.debug(s"OCR result for $chatId:\n$ocrResult")
        ocrResult.toEither
          .leftMap { exc ⇒
            val errorId = UUID.randomUUID()
            logger.warn(s"OCR error [$errorId]:", exc)
            messages.format("errors.save.internal", errorId) → None
          }
      })
      driverInfo ← EitherT(IO {
        val lines = ocrData
          .split("\n")
          .map(
            _.replace(" :", "")
              .replace(" °", "")
              .replace(" #", "")
              .replace(" _", "")
              .replace(" /", "")
              .trim)
          .filter(_.nonEmpty)
          .map(_.toUpperCase)
        val ownerIdx = lines.indexWhere(_.startsWith("УЛАСНІК"))
        val addressIdx = lines.indexWhere(_.startsWith("АДРАС:"))
        if (ownerIdx > -1 && addressIdx > -1) {
          (for {
            vehicleId ← lines
              .take(ownerIdx)
              .flatMap(VehicleIdRegex.findFirstMatchIn)
              .headOption
              .map { m ⇒
                m.group(1) → m.group(2)
              }
            owner ← {
              val nameLines = lines
                .slice(ownerIdx + 1, addressIdx)
                .zipWithIndex
                .filter(_._2 % 2 == 0)
                .take(3)
              if (nameLines.length == 3) {
                Some(s"${nameLines(0)._1} ${nameLines(1)._1} ${nameLines(2)._1}")
              } else {
                None
              }
            }
          } yield {
            DriverInfo(
              id = chatId,
              fullName = owner,
              licenseSeries = vehicleId._1,
              licenseNumber = vehicleId._2)
          }).toRight[(String, Option[String])] {
            val errorId = UUID.randomUUID()
            logger.warn(s"Failed to recognize data from photo [$errorId]")
            messages.format("errors.save.internal", errorId) → None
          }
        } else {
          val errorId = UUID.randomUUID()
          logger.warn(s"Failed to recognize data from photo [$errorId]")
          Left(messages.format("errors.save.internal", errorId) → None)
        }
      })
    } yield driverInfo

}

object SpeedingFinesCheckerBot {
  private final val VehicleIdRegex = """([А-Я]{3})\s*([0-9]{7})""".r
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

  private def fileRequest(token: String, path: String): HttpRequest =
    Http(s"https://api.telegram.org/file/bot$token/$path")
      .method("GET")

  private def getUserId(user: User): String = user.username.map("@" + _).getOrElse(user.id.toString)
}
