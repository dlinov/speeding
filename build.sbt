enablePlugins(DockerPlugin, JavaAppPackaging)

name := "speeding"

version := "0.5.5"

scalaVersion := "2.12.8"

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe" % "config" % "1.3.3",
  "com.bot4s" %% "telegram-core" % "4.0.0-RC2",
  "org.tpolecat" %% "doobie-core" % "0.6.0",
  "org.tpolecat" %% "doobie-postgres" % "0.6.0",
  "io.monix" %% "monix-execution" % "3.0.0-RC2",
  "org.scalaj" %% "scalaj-http" % "2.4.1",
  "org.scala-lang.modules" %% "scala-xml" % "1.1.0",
  "org.apache.commons" % "commons-text" % "1.4",
  "org.jmotor" %% "scala-i18n" % "1.0.6",

  "com.h2database" % "h2" % "1.4.197" % Test,
  "org.scalatest" %% "scalatest" % "3.0.5" % Test)

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-explaintypes",
  "-feature",
  "-language:higherKinds",
  "-unchecked",
  "-Xcheckinit",
  "-Xfuture",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ypartial-unification",
  "-Yrangepos",
  "-Ywarn-dead-code",
  "-Ywarn-infer-any",
  "-Ywarn-nullary-override",
  "-Ywarn-unused-import")

dockerBaseImage := "openjdk:11-jre-slim"