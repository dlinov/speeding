package io.github.dlinov.speeding

import java.net.URI

import cats.effect.{ContextShift, IO}
import cats.effect.internals.IOContextShift
import com.typesafe.config.ConfigFactory
import io.github.dlinov.speeding.dao.{Dao, PostgresDao}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.io.Source
import scala.util.Try

object Boot extends App {
  /*private def memoryInfo: String = {
    val rt = Runtime.getRuntime
    val mb = 1 << 20
    s"free: ${rt.freeMemory() / mb}MB\ttotal: ${rt.totalMemory() / mb}MB\tmax: ${rt.maxMemory() / mb}MB"
  }*/

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val config = ConfigFactory.load()
  private implicit val contextShift: ContextShift[IO] = IOContextShift.global

  // Fetch the token from an environment variable or untracked file.
  private val token = Try(config.getString("bot.token"))
    .getOrElse(Source.fromFile("bot.token").getLines().mkString)

  private val interval = Duration.fromNanos(config.getDuration("bot.interval").toNanos)

  private final val correctJdbcPrefix = "jdbc:postgresql://"
  private val dbUri = new URI(config.getString("db.postgres.url"))
  private val (user, password) = {
    val parts = dbUri.getAuthority.takeWhile(_ != '@').split(':')
    parts.head → parts.last
  }
  private val dbUrl = correctJdbcPrefix + dbUri.getHost + ":" + dbUri.getPort + dbUri.getPath
  private val dao: Dao = new PostgresDao(dbUrl, user, password)
  dao.createSchemaIfMissing().unsafeRunSync()

  private val tessDataPath = config.getString("tesseract.datapath")

  private val bot = new SpeedingFinesCheckerBot(token, dao, tessDataPath)
  private val botScheduler = bot.system.scheduler
  private implicit val botExecutionContext: ExecutionContext = bot.executionContext

  bot.run()
  // botScheduler.schedule(5.seconds, 1.minute, () ⇒ logger.debug(memoryInfo))
  botScheduler.schedule(10.seconds, interval, () ⇒ bot.performCheckForAllDrivers())
  logger.info("Bot has been started")
}
