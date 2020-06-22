package io.github.dlinov.speeding

import java.net.URI

import cats.effect.ExitCase.Canceled
import cats.effect.{Blocker, ExitCode, IO, IOApp, Resource}
import com.softwaremill.sttp.SttpBackend
import com.softwaremill.sttp.asynchttpclient.cats.AsyncHttpClientCatsBackend
import com.typesafe.config.ConfigFactory
import doobie.ExecutionContexts
import doobie.hikari.HikariTransactor
import io.github.dlinov.speeding.dao.PostgresDao
import monix.execution.Scheduler
import org.flywaydb.core.Flyway
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._
import scala.io.Source
import scala.util.Try

object Boot extends IOApp {
  private final val correctJdbcPrefix = "jdbc:postgresql://"
  private val connectionEC = ExecutionContexts.fixedThreadPool[IO](4)
  private val blocker = Blocker[IO]
  private def transactor(
      dbUri: String,
      user: String,
      password: String
  ): Resource[IO, HikariTransactor[IO]] =
    for {
      ce <- connectionEC
      be <- blocker
      xa <-
        HikariTransactor
          .newHikariTransactor[IO]("org.postgresql.Driver", dbUri, user, password, ce, be)
    } yield xa

  override def run(args: List[String]): IO[ExitCode] = {
    for {
      logger <- IO(LoggerFactory.getLogger(this.getClass))
      config <- IO(ConfigFactory.load())
      dbConfig <- IO {
        val rawDbUrl = config.getString("db.postgres.url")
        val dbUri = new URI(rawDbUrl)
        Option(dbUri.getAuthority)
          .map { authority =>
            val parts = authority.takeWhile(_ != '@').split(':')
            val (user, password) = parts.head → parts.last
            val dbUrl = correctJdbcPrefix + dbUri.getHost + ":" + dbUri.getPort + dbUri.getPath
            (dbUrl, user, password)
          }
          .getOrElse((rawDbUrl, "", ""))
      }
      (dbUri, user, password) = dbConfig
      _ <- runFlyway(dbUri, user, password)
      exitCode <- transactor(dbUri, user, password).use { xa =>
        (for {
          // Fetch the token from an environment variable or untracked file.
          token ← IO {
            Try(config.getString("bot.token"))
              .getOrElse {
                val src = Source.fromFile("bot.token")
                val token = src.getLines().mkString
                src.close()
                token
              }
          }
          interval ← IO(Duration.fromNanos(config.getDuration("bot.interval").toNanos))
          dao ← IO(new PostgresDao(xa))
          tessDataPath ← IO(config.getString("tesseract.datapath"))
          bot ← IO {
            implicit val backend: SttpBackend[IO, Nothing] = AsyncHttpClientCatsBackend[IO]()
            new SpeedingFinesCheckerBot(token, dao, tessDataPath)
          }
          _ ← IO(logger.info("Starting bot..."))
          botFiber ← bot.run().start
          _ ← IO(logger.info("Bot has been started"))
          botScheduler ← IO(Scheduler.io("fines-check-scheduler"))
          checkFinesTask ← IO {
            botScheduler.scheduleAtFixedRate(10.seconds, interval)(bot.performCheckForAllDrivers())
          }
          loggingFiber <- scheduleDebugLog(logger, 1.minute).start
          _ ← IO(logger.info("Periodic task of checking of new fines has been started"))
          _ <- botFiber.join
          _ <- loggingFiber.join
          _ <- IO(checkFinesTask.cancel())
        } yield ExitCode.Success).guaranteeCase {
          case Canceled =>
            logger.warn("Interrupted")
            releaseResources()
          case _ =>
            releaseResources()
        }
      }
    } yield exitCode
  }

  private def runFlyway(dbUri: String, user: String, password: String) = {
    IO {
      val fw = Flyway.configure().dataSource(dbUri, user, password).load()
      fw.migrate()
    }
  }

  private def scheduleDebugLog(logger: Logger, duration: FiniteDuration): IO[Unit] = {
    for {
      _ <- IO.sleep(duration)
      _ <- IO(logger.info(memoryInfo))
      _ <- scheduleDebugLog(logger, duration)
    } yield ()
  }

  private def memoryInfo: String = {
    val rt = Runtime.getRuntime
    val mb = 1 << 20
    s"free: ${rt.freeMemory() / mb}MB\ttotal: ${rt.totalMemory() / mb}MB\tmax: ${rt.maxMemory() / mb}MB"
  }

  private def releaseResources(): IO[Unit] =
    IO {
//    checkFinesTask.cancel()
//    bot.shutdown()
    }
}
