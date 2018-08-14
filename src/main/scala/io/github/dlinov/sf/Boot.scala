package io.github.dlinov.sf

import scala.io.Source

object Boot extends App {
  // Fetch the token from an environment variable or untracked file.
  lazy val token = scala.util.Properties
    .envOrNone("BOT_TOKEN")
    .getOrElse(Source.fromFile("bot.token").getLines().mkString)

  new SpeedingFinesCheckerBot(token).run()
}
