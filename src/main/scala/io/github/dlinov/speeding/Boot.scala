package io.github.dlinov.speeding

import java.net.URI

import io.github.dlinov.speeding.dao.{Dao, PostgresDao}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.io.Source

object Boot extends App {
  private def memoryInfo: String = {
    val rt = Runtime.getRuntime
    val mb = 1 << 20
    s"free: ${rt.freeMemory() / mb}MB\ttotal: ${rt.totalMemory() / mb}MB\tmax: ${rt.maxMemory() / mb}MB"
  }

  private val logger = LoggerFactory.getLogger(this.getClass)
  // Fetch the token from an environment variable or untracked file.
  private lazy val token = scala.util.Properties
    .envOrNone("BOT_TOKEN")
    .getOrElse(Source.fromFile("bot.token").getLines().mkString)
  private final val correctJdbcPrefix = "jdbc:postgresql://"
  private val dbUri = new URI(scala.util.Properties
    .envOrNone("DATABASE_URL")
    .getOrElse("postgres://postgres:password@127.0.0.1:5432/speeding"))
  private val (user, password) = {
    val parts = dbUri.getAuthority.takeWhile(_ != '@').split(':')
    parts.head → parts.last
  }
  private val dbUrl = correctJdbcPrefix + dbUri.getHost + ":" + dbUri.getPort + dbUri.getPath
  private val dao: Dao = new PostgresDao(dbUrl, user, password)
  dao.createSchemaIfMissing().unsafeRunSync()
  private val bot = new SpeedingFinesCheckerBot(token, dao)
  private val botScheduler = bot.system.scheduler
  private implicit val botExecutionContext: ExecutionContext = bot.executionContext

  bot.run()
  botScheduler.schedule(5.seconds, 1.minute, () ⇒ logger.debug(memoryInfo))
  botScheduler.schedule(10.seconds, 5.minutes, () ⇒ bot.performCheckForAllDrivers())
}
