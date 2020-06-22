package io.github.dlinov.speeding

import java.net.HttpCookie
import java.nio.file.{Files, Paths}
import java.util.{Locale, UUID}

import cats.data.EitherT
import cats.{Applicative, Functor}
import cats.effect.{ContextShift, IO, Sync}
import cats.instances.either._
import cats.instances.list._
import cats.instances.option._
import cats.instances.string._
import cats.syntax.either._
import cats.syntax.functor._
import cats.syntax.semigroup._
import cats.syntax.traverse._
import com.bot4s.telegram.api.declarative.{Callbacks, Commands}
import com.bot4s.telegram.cats.{Polling, TelegramBot}
import com.bot4s.telegram.methods.{GetFile, SendMessage}
import com.bot4s.telegram.models._
import com.softwaremill.sttp.SttpBackend
import io.github.dlinov.speeding.BotReply._
import io.github.dlinov.speeding.BotAck._
import io.github.dlinov.speeding.dao.{Dao, DaoProvider}
import io.github.dlinov.speeding.model.{BotUser, DriverInfo}
import io.github.dlinov.speeding.model.parser.ResponseParser
import net.sourceforge.tess4j.Tesseract
import scalaj.http._

import scala.util.Try

// TODO: change to F[_] here
class SpeedingFinesCheckerBot(
    token: String,
    override val dao: Dao[IO],
    tessDataPath: String
)(implicit
    backend: SttpBackend[IO, Nothing],
    cs: ContextShift[IO]
) extends TelegramBot[IO](token, backend)
    with Commands[IO]
    with Polling[IO]
    with Callbacks[IO]
    with DaoProvider[IO]
    with Localized[IO]
    with LocalizedHelp[IO] {

  import SpeedingFinesCheckerBot._

  type EitherS[A] = Either[String, A]
  type IOEitherS[A] = IO[EitherS[A]]
  type Rs = (String, Option[String])
  implicit val applicative: Applicative[IOEitherS] =
    Applicative[IO].compose(Applicative[EitherS])
  override val sync: Sync[IO] = Sync[IO]
  override val fnctr: Functor[IO] = Functor[IO]

  private val ioEitherFunctor = Functor[IO].compose(Functor[dao.DaoEither])
  private val ioEitherListFunctor = ioEitherFunctor.compose(Functor[List])
  private val tess: Tesseract = {
    val t = new Tesseract()
    t.setDatapath(tessDataPath)
    t.setLanguage("rus+bel") // tesseract 4 works well with rus only
    t
  }

  onCommandWithHelp("start")("bot.description.start") { implicit msg =>
    skipBots {
      for {
        chatId <- IO.pure(msg.source)
        _ <- IO(logger.info(s"Chat $chatId asked /start"))
        userLocale ← getUserLocale
        driverResp ← dao.findDriver(chatId)
        botReply = makeResponseForStart(chatId, driverResp)(userLocale)
        _ <- reply(
          text = botReply.text,
          parseMode = botReply.parseMode,
          disableWebPagePreview = botReply.disableWebPagePreview,
          disableNotification = botReply.disableNotification,
          replyToMessageId = botReply.replyToMessageId,
          replyMarkup = botReply.replyMarkup
        )
      } yield ()
    }
  }

  onCommandWithHelp("forcecheck")("bot.description.forcecheck") { implicit msg ⇒
    skipBots {
      for {
        chatId <- IO.pure(msg.source)
        _ <- IO(logger.info(s"Chat $chatId asked to check its fines"))
        userLocale ← getUserLocale
        driverResp ← dao.findDriver(chatId)
        botReply = makeResponseForForceCheck(chatId, driverResp)(userLocale)
        _ <- reply(
          text = botReply.text,
          parseMode = botReply.parseMode,
          disableWebPagePreview = botReply.disableWebPagePreview,
          disableNotification = botReply.disableNotification,
          replyToMessageId = botReply.replyToMessageId,
          replyMarkup = botReply.replyMarkup
        )
      } yield ()
    }
  }

  onCommandWithHelp("lang")("bot.description.updLang") { implicit msg ⇒
    skipBots {
      for {
        chatId <- IO.pure(msg.source)
        _ <- IO(logger.info(s"Chat $chatId asked to change bot language"))
        userLocale ← getUserLocale
        userResp ← dao.findUser(chatId)
        botReply = makeResponseForLang(chatId, userResp)(userLocale)
        _ <- reply(
          text = botReply.text,
          parseMode = botReply.parseMode,
          disableWebPagePreview = botReply.disableWebPagePreview,
          disableNotification = botReply.disableNotification,
          replyToMessageId = botReply.replyToMessageId,
          replyMarkup = botReply.replyMarkup
        )
      } yield ()
    }
  }

  onCommandWithHelp("profile")("bot.description.profile") { implicit msg ⇒
    skipBots {
      for {
        chatId <- IO.pure(msg.source)
        _ <- IO(logger.info(s"Chat $chatId asked to show profile data"))
        userLocale ← getUserLocale
        profileResp ← dao.findDriver(chatId)
        botReply = makeResponseForProfile(chatId, profileResp)(userLocale)
        _ <- reply(
          text = botReply.text,
          parseMode = botReply.parseMode,
          disableWebPagePreview = botReply.disableWebPagePreview,
          disableNotification = botReply.disableNotification,
          replyToMessageId = botReply.replyToMessageId,
          replyMarkup = botReply.replyMarkup
        )
      } yield ()
    }
  }

  onCallbackQuery { implicit callbackQuery ⇒
    for {
      _ <- IO(logger.debug(s"cbq: $callbackQuery}"))
      // You must acknowledge callback queries, even if there's no response.
      // e.g. just ackCallback()
      userId <- IO.pure(callbackQuery.from.id)
      _ <- {
        callbackQuery.data
          .map { lang =>
            for {
              _ <- IO(logger.debug(s"data: $lang"))
              desiredLocale <- IO(new Locale(lang))
              resp ← dao.updateUser(userId, lang)
              ack =
                resp
                  .fold(
                    err ⇒ {
                      val errId = UUID.randomUUID()
                      logger.warn(
                        s"Failed to update user $userId language [$errId]: ${err.message}"
                      )
                      messages.format("errors.internal", errId)(desiredLocale)
                    },
                    _ ⇒ messages.format("lang.changed")(desiredLocale)
                  )
                  .asPlainAck
              _ <- ackCallback(
                text = ack.text,
                showAlert = ack.showAlert,
                cacheTime = ack.cacheTime,
                url = ack.url
              )
            } yield ()
          }
          .getOrElse(IO.unit)
      }
    } yield ()
  }

  onCommandWithHelp("stop", "delete", "remove")("bot.description.removeData") { implicit msg ⇒
    skipBots {
      val chatId = msg.source
      logger.info(s"Chat $chatId asked to remove driver data")
      for {
        userLocale ← getUserLocale
        deleteInfoResp ← dao.deleteUserData(chatId)
        botReply = makeResponseForDelete(chatId, deleteInfoResp)(userLocale)
        _ <- reply(
          text = botReply.text,
          parseMode = botReply.parseMode,
          disableWebPagePreview = botReply.disableWebPagePreview,
          disableNotification = botReply.disableNotification,
          replyToMessageId = botReply.replyToMessageId,
          replyMarkup = botReply.replyMarkup
        )
      } yield ()
    }
  }

  onMessage { implicit msg ⇒
    val msgText = msg.text
    val chatId = msg.source
    if (msgText.forall(!_.startsWith("/"))) {
      skipBots {
        val replies = (for {
          locale ← EitherT.liftF(getUserLocale)
          driverInfo ← msg.photo.fold {
            EitherT(IO(parseText(msgText, chatId)(locale)))
          } {
            parsePhotos(_, chatId)(locale)
          }
          replies ← EitherT {
            dao
              .updateDriver(chatId, driverInfo)
              .map(x => x)
              .map(
                _.fold[Either[Rs, Rs]](
                  err ⇒ {
                    val errorId = UUID.randomUUID()
                    logger.warn(
                      s"Couldn't update data for $chatId [$errorId]: ${err.message}"
                    )
                    Either.left[Rs, Rs](
                      messages
                        .format("errors.save.internal", errorId)(locale) → None
                    )
                  },
                  _ ⇒ {
                    logger.info(s"Chat $chatId updated its data to $driverInfo")
                    val checkFinesResp =
                      performCheckForSingleDriver(chatId, driverInfo)(locale)
                    Either.right[Rs, Rs](
                      messages
                        .format("data.saved")(locale) → Some(checkFinesResp)
                    )
                  }
                )
              )
          }
        } yield replies).merge
        for {
          (updDataResp, maybeFinesResp) <- replies
          _ <- reply(updDataResp)
          _ <- maybeFinesResp.map(reply(_)).getOrElse(IO.unit)
        } yield ()
      }
    } else {
      IO.unit
    }
  }

  def performCheckForAllDrivers(): Unit =
    Try(retrieveCookies)
      .map { cookies ⇒
        val allDrivers = ioEitherFunctor.map(dao.findAll)(_.toList)
        val value = ioEitherListFunctor.map(allDrivers) { driver ⇒
          val userId = driver.id
          dao.findUser(userId).map {
            _.leftMap(err ⇒ s"Cannot query user $userId, but driver info exists: ${err.message}")
              .flatMap(
                _.toRight(s"User $userId was not found, but driver info exists")
              )
              .map { user ⇒
                checkDriverFines(cookies, driver, onlyNew = true)(user.locale)
                  .map(sendMessageTo(userId, _))
              }
              .map(_.toRight("Message was not sent, see logs"))
              .void
          }
        }
        value
          .flatMap(
            _.leftMap(_.message)
              .flatTraverse(_.traverse[IOEitherS, Unit](identity))
          )
          .unsafeRunSync()
      }

  def performCheckForSingleDriver(chatId: Long, driverInfo: DriverInfo)(implicit
      locale: Locale
  ): String =
    Try(retrieveCookies)
      .fold(
        err ⇒ {
          val errorId = UUID.randomUUID()
          logger.warn(
            s"Couldn't retrieve cookies for chat $chatId [$errorId]. $err"
          )
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

  private def sendMessageTo(chatId: Long, text: String): IO[Message] =
    request(SendMessage(chatId, text))
//      .attempt
//      .recover {
//        case TelegramApiException(message, errorCode, maybeCause, _) ⇒
//          val preparedErrorMessage =
//            s"Couldn't send message '$text' to chat $chatId: $message [$errorCode]"
//          maybeCause.fold {
//            logger.warn(preparedErrorMessage)
//          } { cause ⇒
//            logger.warn(s"$preparedErrorMessage. Inner error: ", cause)
//          }
//          None
//      }

  private def makeResponseForStart(
      chatId: Long,
      driverResp: dao.DaoEither[Option[DriverInfo]]
  )(implicit userLocale: Locale): BotReply = {
    driverResp
      .fold(
        err ⇒ {
          val errorId = UUID.randomUUID()
          logger.error(
            s"Couldn't check db for chat $chatId [$errorId]: ${err.message}"
          )
          messages.format("errors.internal", errorId)
        },
        _.fold {
          logger.warn(s"Couldn't find chat $chatId")
          messages.format("errors.chatNotFound")
        } { profile =>
          messages.format("start.knownProfile", profile.fullName)
        }
      )
      .asPlainReply
  }

  private def makeResponseForForceCheck(
      chatId: Long,
      driverResp: dao.DaoEither[Option[DriverInfo]]
  )(implicit userLocale: Locale): BotReply = {
    driverResp
      .fold(
        err ⇒ {
          val errorId = UUID.randomUUID()
          logger.error(
            s"Couldn't check db for chat $chatId [$errorId]: ${err.message}"
          )
          messages.format("errors.internal", errorId)
        },
        _.fold {
          logger.warn(s"Couldn't find chat $chatId")
          messages.format("errors.chatNotFound")
        } {
          performCheckForSingleDriver(chatId, _)
        }
      )
      .asPlainReply
  }

  private def makeResponseForLang(
      chatId: Long,
      userResp: dao.DaoEither[Option[BotUser]]
  )(implicit userLocale: Locale): BotReply = {
    userResp
      .fold(
        err ⇒ {
          val errorId = UUID.randomUUID()
          logger.error(
            s"Couldn't check db for chat $chatId [$errorId]: ${err.message}"
          )
          messages.format("errors.internal", errorId).asPlainReply
        },
        _.fold {
          logger.warn(s"Couldn't find chat $chatId")
          messages.format("errors.chatNotFound").asPlainReply
        } { u ⇒
          val supportedLanguages = Seq(
            "\uD83C\uDDE7\uD83C\uDDFE" → "by",
            "\uD83C\uDDF7\uD83C\uDDFA" → "ru",
            "\uD83C\uDDEC\uD83C\uDDE7" → "en"
          )
          val inlineBtns = InlineKeyboardMarkup(
            Seq(
              supportedLanguages
                .map(lang ⇒
                  InlineKeyboardButton(
                    lang._1,
                    callbackData = Some(lang._2)
                  )
                )
            )
          )
          val currentLanguage = supportedLanguages
            .find(_._2 == u.lang)
            .map(_._1)
            .getOrElse(userLocale.getLanguage)
          BotReply(
            text = messages.format("lang.current", currentLanguage)(u.locale),
            replyMarkup = Some(inlineBtns)
          )
        }
      )
  }

  private def makeResponseForProfile(
      chatId: Long,
      profileResp: dao.DaoEither[Option[DriverInfo]]
  )(implicit userLocale: Locale): BotReply = {
    profileResp
      .fold(
        err ⇒ {
          val errorId = UUID.randomUUID()
          logger.error(
            s"Couldn't check db for chat $chatId [$errorId]: ${err.message}"
          )
          messages.format("errors.internal", errorId)
        },
        _.fold {
          logger.warn(s"Couldn't find chat $chatId")
          messages.format("errors.chatNotFound")
        } { u ⇒
          s"${u.fullName}\n${u.licenseSeries} ${u.licenseNumber}"
        }
      )
      .asPlainReply
  }

  private def makeResponseForDelete(
      chatId: Long,
      deleteInfoResp: dao.DaoEither[Unit]
  )(implicit userLocale: Locale): BotReply = {
    deleteInfoResp
      .fold(
        err ⇒ {
          val errorId = UUID.randomUUID()
          logger.error(
            s"Couldn't clear user $chatId data [$errorId]: ${err.message}"
          )
          messages.format("errors.internal", errorId)
        },
        _ ⇒ messages.format("data.removed")
      )
      .asPlainReply
  }

  private def checkDriverFines(
      cookies: IndexedSeq[HttpCookie],
      driverInfo: DriverInfo,
      onlyNew: Boolean
  )(implicit locale: Locale): Option[String] =
    // TODO: wrap everything to IO?
    (for {
      resp ← Try(speedingReq(driverInfo, cookies).asString).toEither
      activeFines ← ResponseParser.parse(driverInfo.id, resp.body)
      activeFineIds = activeFines.map(_.id).toSet
      existingUnpaidFines ←
        dao
          .findUnpaidDriverFines(driverInfo.id)
          .unsafeRunSync()
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

  private def skipBots(action: ⇒ IO[Unit])(implicit msg: Message): IO[Unit] = {
    msg.from.fold(IO.unit) { user ⇒
      if (!user.isBot) {
        action
      } else {
        IO(logger.info(s"User ${getUserId(user)} is bot, skipping his request"))
      }
    }
  }

  private def parseText(
      msgText: Option[String],
      chatId: Long
  )(implicit locale: Locale): Either[(String, Option[String]), DriverInfo] =
    msgText.map(_.toUpperCase) match {
      case Some(
            InputRegex(
              lastName,
              firstName,
              middleName,
              licenseSeries,
              licenseNumber
            )
          ) ⇒
        Right(
          DriverInfo(
            id = chatId,
            fullName = s"$lastName $firstName $middleName",
            licenseSeries = licenseSeries,
            licenseNumber = licenseNumber
          )
        )
      case _ ⇒
        Left {
          logger.warn(s"'$msgText' from $chatId didn't match input regex")
          messages.format("errors.save.badrequest") → None
        }
    }

  private def parsePhotos(
      photos: Seq[PhotoSize],
      chatId: Long
  )(implicit locale: Locale): EitherT[IO, (String, Option[String]), DriverInfo] =
    for {
      bestFileId ← EitherT.liftF(IO(photos.maxBy(_.width).fileId))
      fileInfo ← EitherT.liftF(client(GetFile(bestFileId)))
      filePath ← EitherT(
        IO(
          fileInfo.filePath
            .toRight {
              val errorId = UUID.randomUUID()
              logger.warn(
                s"Failed to get file $bestFileId from telegram [$errorId]: empty path"
              )
              messages.format("errors.save.internal", errorId) → None
            }
        )
      )
      fileResponse ← EitherT(IO {
        import cats.syntax.either._
        Try(fileRequest(token, filePath).asBytes).toEither
          .leftMap { exc ⇒
            val errorId = UUID.randomUUID()
            logger.warn(
              s"Failed to get file $bestFileId from telegram [$errorId]",
              exc
            )
            messages.format("errors.save.internal", errorId) → None
          }
      })
      fileBytes ← EitherT.fromEither[IO] {
        if (fileResponse.isSuccess) {
          Right(fileResponse.body)
        } else {
          val errorId = UUID.randomUUID()
          logger.warn(
            s"Failed to get file $bestFileId from telegram [$errorId]: $fileResponse"
          )
          Left(messages.format("errors.save.internal", errorId) → None)
        }
      }
      ocrData ← EitherT.fromEither[IO] {
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
      }
      driverInfo ← EitherT(IO {
        val lines = ocrData
          .split("\n")
          .map(
            _.replace(" :", "")
              .replace(" °", "")
              .replace(" #", "")
              .replace(" _", "")
              .replace(" /", "")
              .trim
          )
          .filter(_.nonEmpty)
          .map(_.toUpperCase)
        val ownerIdx = lines.indexWhere(_.startsWith("УЛАСНІК"))
        val addressIdx = lines.indexWhere(_.startsWith("АДРАС:"))
        if (ownerIdx > -1 && addressIdx > -1) {
          (for {
            vehicleId ←
              lines
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
                Some(
                  s"${nameLines(0)._1} ${nameLines(1)._1} ${nameLines(2)._1}"
                )
              } else {
                None
              }
            }
          } yield {
            DriverInfo(
              id = chatId,
              fullName = owner,
              licenseSeries = vehicleId._1,
              licenseNumber = vehicleId._2
            )
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

  private final val SessionCookieReq = Http(
    "http://mvd.gov.by/main.aspx?guid=15791"
  )
  private final val SpeedingBaseCheckReq =
    Http("http://mvd.gov.by/Ajax.asmx/GetExt")
      .method("POST")
      .headers(
        "Host" → "mvd.gov.by",
        "Origin" → "http://mvd.gov.by",
        "Referer" → SessionCookieReq.url,
        "Content-Type" → "application/json; charset=UTF-8"
      )

  private def speedingReq(driverInfo: DriverInfo, cookies: Seq[HttpCookie]): HttpRequest = {
    val fn = driverInfo.fullName
    val ls = driverInfo.licenseSeries
    val ln = driverInfo.licenseNumber
    val json =
      s"""{"GuidControl": 2091, "Param1": "$fn", "Param2": "$ls", "Param3": "$ln"}"""
    SpeedingBaseCheckReq
      .cookies(cookies)
      .postData(json)
  }

  private def fileRequest(token: String, path: String): HttpRequest =
    Http(s"https://api.telegram.org/file/bot$token/$path")
      .method("GET")

  private def getUserId(user: User): String =
    user.username.map("@" + _).getOrElse(user.id.toString)
}
