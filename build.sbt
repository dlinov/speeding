enablePlugins(DockerPlugin, JavaAppPackaging)

name := "speeding"

version := "0.7.0"

scalaVersion := "2.12.8"

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe" % "config" % "1.3.3",
  "com.bot4s" %% "telegram-core" % "4.0.0-RC2",
  "org.tpolecat" %% "doobie-core" % "0.6.0",
  "org.tpolecat" %% "doobie-postgres" % "0.6.0",
  "io.monix" %% "monix-execution" % "3.0.0-RC2",
  "org.scala-lang.modules" %% "scala-xml" % "1.1.1",
  "org.apache.commons" % "commons-text" % "1.6",
  "org.jmotor" %% "scala-i18n" % "1.0.6",
  "net.sourceforge.tess4j" % "tess4j" % "4.3.1",

  "com.h2database" % "h2" % "1.4.197" % Test,
  "org.scalatest" %% "scalatest" % "3.0.5" % Test)

libraryDependencies --= Seq(
  "log4j" % "log4j" % "1.2.17",
  "org.slf4j" % "log4j-over-slf4j" % "1.7.25"
)

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-explaintypes",
  "-feature",
  "-language:postfixOps",
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

assemblyMergeStrategy in assembly := {
  case PathList(ps @ _*) if ps.last endsWith ".html" ⇒ MergeStrategy.first
  case "application.conf"                            ⇒ MergeStrategy.concat
  case x if x endsWith ".txt"                        ⇒ MergeStrategy.discard
  // reduce jar size by dropping tesseract windows dll
  case x if x endsWith ".dll"                        ⇒ MergeStrategy.discard
  case x if x.contains("slf4j-api")                  ⇒ MergeStrategy.last
  case x if x.contains("log4j")                      ⇒ MergeStrategy.last
  case x if x.contains("org/apache/commons/logging") ⇒ MergeStrategy.last
  case x ⇒
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}