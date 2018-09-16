enablePlugins(DockerPlugin, JavaAppPackaging)

name := "speeding"

version := "0.4"

scalaVersion := "2.12.6"

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe" % "config" % "1.3.3",
  "info.mukel" %% "telegrambot4s" % "3.0.15",
  "org.tpolecat" %% "doobie-core" % "0.5.3",
  "org.tpolecat" %% "doobie-postgres" % "0.5.3",
  "org.scalaj" %% "scalaj-http" % "2.4.1",
  "org.scala-lang.modules" %% "scala-xml" % "1.1.0",
  "org.apache.commons" % "commons-text" % "1.4",
  "org.jmotor" %% "scala-i18n" % "1.0.6",

  "com.h2database" % "h2" % "1.4.197" % Test,
  "org.scalatest" %% "scalatest" % "3.0.5" % Test)

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-encoding", "UTF-8",
  "-explaintypes",
  "-feature",
  "-language:postfixOps",
  "-language:higherKinds",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ypartial-unification", // advised by https://github.com/typelevel/cats
  "-Ywarn-dead-code",
  "-Ywarn-unused")

dockerBaseImage := "openjdk:11-jre-slim"