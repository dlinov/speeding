ThisBuild / resolvers ++= Seq(
    Resolver.sonatypeRepo("public"),
    Resolver.sonatypeRepo("releases"),
    Resolver.sonatypeRepo("snapshots"),
)
ThisBuild / scalaVersion := "2.12.11"
ThisBuild / scalacOptions ++= Seq(
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
ThisBuild / name := "speeding"
ThisBuild / version := "1.0.0-SNAPSHOT"

val batikVersion = "1.12"
val circeVersion = "0.13.0"
val doobieVersion = "0.8.8"
val fs2Version = "2.3.0"
val http4sVersion = "0.21.4"
val logbackVersion = "1.2.3"
val scrimageVersion = "4.0.4"
val typesafeConfigVersion = "1.4.0"
val bot4sTelegramVersion = "4.4.0-RC2"
val monixVersion = "3.2.1"
val scalaXmlVersion = "1.3.0"
val commonsTestVersion = "1.8"
val scalaI18nVersion = "1.0.7"
val tess4jVersion = "4.5.1"
val sttpBackendVersion = "1.5.19" // should match with sttp from telegram-core
val h2Version = "1.4.200"
val scalatestVersion = "3.1.0"

lazy val bot = project.in(file("bot"))
  .dependsOn(`captcha-solver`)
  .enablePlugins(DockerPlugin, JavaAppPackaging)
  .settings(
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      "com.typesafe" % "config" % typesafeConfigVersion,
      "com.bot4s" %% "telegram-core" % bot4sTelegramVersion,
      "org.tpolecat" %% "doobie-core" % doobieVersion,
      "org.tpolecat" %% "doobie-postgres" % doobieVersion,
      "io.monix" %% "monix-execution" % monixVersion,
      "org.scala-lang.modules" %% "scala-xml" % scalaXmlVersion,
      "org.apache.commons" % "commons-text" % commonsTestVersion,
      "org.jmotor" %% "scala-i18n" % scalaI18nVersion,
      "net.sourceforge.tess4j" % "tess4j" % tess4jVersion,
      "com.softwaremill.sttp" %% "async-http-client-backend-cats" % sttpBackendVersion,

      "com.h2database" % "h2" % h2Version % Test,
      "org.scalatest" %% "scalatest" % scalatestVersion % Test),
    libraryDependencies --= Seq(
      "log4j" % "log4j" % "1.2.17",
      "org.slf4j" % "log4j-over-slf4j" % "1.7.25"
    ),
    dockerBaseImage := "openjdk:11-jre",
    assembly / assemblyMergeStrategy := {
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
    },
  )

lazy val `captcha-solver` = Project("captcha-solver", file("captcha-solver"))
  .settings(
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
      "com.sksamuel.scrimage" % "scrimage-core" % scrimageVersion,
      "com.sksamuel.scrimage" % "scrimage-filters" % scrimageVersion,
      "com.sksamuel.scrimage" %% "scrimage-scala" % scrimageVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "org.apache.xmlgraphics" % "batik-rasterizer" % batikVersion,
      "org.http4s" %% "http4s-blaze-client" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
    )
  )