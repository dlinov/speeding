package io.github.dlinov.speeding

import java.net.URI

import cats.effect.{ExitCode, IO, IOApp}
import com.softwaremill.sttp.SttpBackend
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import com.typesafe.config.ConfigFactory
import io.github.dlinov.speeding.dao.PostgresDao
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Source
import scala.util.Try

object Boot extends IOApp {
  private final val correctJdbcPrefix = "jdbc:postgresql://"

  def run(args: List[String]): IO[ExitCode] = {
    for {
      logger ← IO(LoggerFactory.getLogger(this.getClass))
      config ← IO(ConfigFactory.load())
      // Fetch the token from an environment variable or untracked file.
      token ← IO(Try(config.getString("bot.token"))
        .getOrElse(Source.fromFile("bot.token").getLines().mkString))
      interval ← IO(Duration.fromNanos(config.getDuration("bot.interval").toNanos))
      dao ← IO {
        val dbUri = new URI(config.getString("db.postgres.url"))
        val (user, password) = {
          val parts = dbUri.getAuthority.takeWhile(_ != '@').split(':')
          parts.head → parts.last
        }
        val dbUrl = correctJdbcPrefix + dbUri.getHost + ":" + dbUri.getPort + dbUri.getPath
        new PostgresDao(dbUrl, user, password)
      }
      _ ← dao.createSchemaIfMissing()
      bot ← IO {
        implicit val backend: SttpBackend[Future, Nothing] = OkHttpFutureBackend()
        new SpeedingFinesCheckerBot(token, dao)
      }
      _ ← IO.fromFuture(IO(bot.run()))
      _ ← IO(logger.info("Bot has been started"))
      // private val botScheduler = bot.system.scheduler
      // private implicit val botExecutionContext: ExecutionContext = bot.executionContext
      // botScheduler.schedule(5.seconds, 1.minute, () ⇒ logger.debug(memoryInfo))
      // botScheduler.schedule(10.seconds, interval, () ⇒ bot.performCheckForAllDrivers())
      never ← IO.never.map(_ ⇒ ExitCode.Success)
    } yield never

  }

  /*private def memoryInfo: String = {
    val rt = Runtime.getRuntime
    val mb = 1 << 20
    s"free: ${rt.freeMemory() / mb}MB\ttotal: ${rt.totalMemory() / mb}MB\tmax: ${rt.maxMemory() / mb}MB"
  }*/
}
