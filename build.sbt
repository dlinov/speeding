enablePlugins(DockerPlugin, JavaAppPackaging)

name := "speeding"

version := "0.6.0"

scalaVersion := "2.12.8"

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe" % "config" % "1.3.3",
  "info.mukel" %% "telegrambot4s" % "3.0.16",
  "org.tpolecat" %% "doobie-core" % "0.6.0",
  "org.tpolecat" %% "doobie-postgres" % "0.6.0",
  "org.scalaj" %% "scalaj-http" % "2.4.1",
  "org.scala-lang.modules" %% "scala-xml" % "1.1.0",
  "org.apache.commons" % "commons-text" % "1.4",
  "org.jmotor" %% "scala-i18n" % "1.0.6",
  "net.sourceforge.tess4j" % "tess4j" % "4.3.1",

  "com.h2database" % "h2" % "1.4.197" % Test,
  "org.scalatest" %% "scalatest" % "3.0.5" % Test)

libraryDependencies --= Seq(
  "log4j" % "log4j" % "1.2.17",
  "org.slf4j" % "log4j-over-slf4j" % "1.7.25"
)

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

assemblyMergeStrategy in assembly := {
  case PathList(ps @ _*) if ps.last endsWith ".html" ⇒ MergeStrategy.first
  case "application.conf"                            ⇒ MergeStrategy.concat
  case x if x endsWith ".txt"                        ⇒ MergeStrategy.discard
  case x if x.contains("slf4j-api")                  ⇒ MergeStrategy.last
  case x if x.contains("log4j")                      ⇒ MergeStrategy.last
  case x if x.contains("org/apache/commons/logging") ⇒ MergeStrategy.last
  case x ⇒
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}