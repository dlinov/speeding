package io.github.dlinov.speeding

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
  private val dbUri = scala.util.Properties
    .envOrNone("DATABASE_URL")
    .getOrElse("postgresql://localhost:5432/speeding")
  private val dao: Dao = new PostgresDao("jdbc:" + dbUri, "postgres", "password")
  private val bot = new SpeedingFinesCheckerBot(token, dao)
  private val botScheduler = bot.system.scheduler
  private implicit val botExecutionContext: ExecutionContext = bot.executionContext

  bot.run()
  botScheduler.schedule(5.seconds, 1.minute, () ⇒ logger.debug(memoryInfo))
  botScheduler.schedule(10.seconds, 5.minutes, () ⇒ bot.performCheckForAllDrivers())
}
