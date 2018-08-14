import scalariform.formatter.preferences._

enablePlugins(DockerPlugin, JavaAppPackaging)

name := "speeding-fines"

version := "0.1"

scalaVersion := "2.12.6"

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "info.mukel" %% "telegrambot4s" % "3.0.15",
  "org.scalaj" %% "scalaj-http" % "2.4.1")
  //"org.telegram" % "telegramapi" % "66.2",
  //"org.typelevel" %% "cats-core" % "1.1.0",
  //"net.debasishg" %% "redisclient" % "3.5",
  //"com.softwaremill.macwire" %% "macros" % "2.3.1" % "provided")

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-encoding", "UTF-8",
  "-explaintypes",
  "-feature",
  "-language:postfixOps",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ypartial-unification", // advised by https://github.com/typelevel/cats
  "-Ywarn-dead-code",
  "-Ywarn-unused")

dockerBaseImage := "openjdk:10-jre-slim"

scalariformPreferences := scalariformPreferences.value
  .setPreference(DanglingCloseParenthesis, Prevent)
  .setPreference(RewriteArrowSymbols, true) // allows not to force this option in IDE
  .setPreference(SpacesAroundMultiImports, false)
  .setPreference(DoubleIndentConstructorArguments, true) // http://docs.scala-lang.org/style/declarations.html#classes
  .setPreference(NewlineAtEndOfFile, true)