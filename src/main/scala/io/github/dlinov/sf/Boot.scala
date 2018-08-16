package io.github.dlinov.sf

import io.github.dlinov.sf.dao.{Dao, PostgresDao}

import scala.io.Source

object Boot extends App {
  // Fetch the token from an environment variable or untracked file.
  lazy val token = scala.util.Properties
    .envOrNone("BOT_TOKEN")
    .getOrElse(Source.fromFile("bot.token").getLines().mkString)
  val dbUri = scala.util.Properties
    .envOrNone("DATABASE_URL")
    .getOrElse("postgresql://localhost:5432/speeding")

  private val dao: Dao = new PostgresDao("jdbc:" + dbUri, "postgres", "password")

  new SpeedingFinesCheckerBot(token, dao).run()
}
