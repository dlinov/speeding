package io.github.dlinov.speeding.captcha

import java.nio.file.Path
import java.time.Instant

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import fs2.io.{file => fs2File}
import fs2.{Pipe, Stream}
import io.circe.Json
import io.circe.jawn.CirceSupportParser
import io.github.dlinov.speeding.captcha.ImageProcessor.{DigitsAndSign, RichImage}
import jawnfs2._
import org.http4s._
import org.http4s.client.blaze._
import org.typelevel.jawn.Facade

import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._

object DatagenBoot extends IOApp with DatasetSettings with ImageProcessor {

  private implicit val f: Facade[Json] = new CirceSupportParser(None, false).facade
  private val headers = Headers.of(
    Header("authority", "www.mvd.gov.by"),
    Header("pragma", "no-cache"),
    Header("cache-control", "no-cache"),
    Header("accept", "application/json, text/plain, */*"),
    Header("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4101.0 Safari/537.36"),
    Header("dnt", "1"),
    Header("sec-fetch-site", "same-origin"),
    Header("sec-fetch-mode", "cors"),
    Header("sec-fetch-dest", "empty"),
    Header("referer", "https://www.mvd.gov.by/ru/login"),
  )
  private val baseUri = "https://www.mvd.gov.by/api/captcha/main?unique="
  private val baseRequest = Request[IO](method = Method.GET, headers = headers)

  override def run(args: List[String]): IO[ExitCode] = {
    converter.compile.drain.as(ExitCode.Success)
  }

  val converter: Stream[IO, Unit] = Stream.resource(Blocker[IO]).flatMap { blocker =>
    val ensureDstExist = for {
      _ <- checkForExistence(digitsPath, blocker)
      _ <- checkForExistence(signsPath, blocker)
    } yield IO.unit
    val savePipe = save(_, blocker)
    val preStream = Stream.eval(ensureDstExist)
    val mainStream = Stream.awakeEvery[IO](800.millis)
      // or Stream.range(0, 10000).metered(800.millis)
      .map(_ => Instant.now.toEpochMilli)
      .map { epochMs =>
        baseRequest.withUri(Uri.unsafeFromString(baseUri + epochMs.toString))
      }
      .flatMap(captchaStream)
      .flatMap(jsObj => Stream.emits((jsObj \\ "data").flatMap(_.asString)))
      .map(rasterize)
      .map(cutImageToThree)
      .map(bw)
      .zipWithIndex
      .flatMap { case (DigitsAndSign(d1, d2, s), _) =>
        val millis = Instant.now.toEpochMilli
        val fn1 = digitsPath.resolve(s"captcha-$millis-1.png")
        val fn2 = digitsPath.resolve(s"captcha-$millis-2.png")
        val fnS = signsPath.resolve(s"captcha-$millis.png")
        val s1 = d1.byteStream.through(savePipe(fn1))
        val s2 = d2.byteStream.through(savePipe(fn2))
        val s3 = s.byteStream.through(savePipe(fnS))
        s1.merge(s2).merge(s3)
      }
    preStream.flatMap(_ => mainStream)
  }

  private def checkForExistence(targetPath: Path, blocker: Blocker): IO[Path] = for {
    exists <- fs2File.exists[IO](blocker, targetPath)
    dir <- if (exists) IO.pure(targetPath) else fs2File.createDirectories[IO](blocker, targetPath)
  } yield dir

  private def captchaStream(
    req: Request[IO],
  ): Stream[IO, Json] =
    for {
      client <- BlazeClientBuilder[IO](global).stream
      res <- client.stream(req)
      jsonObj <- res.body.chunks.parseJsonStream
    } yield jsonObj

  private def save(fileName: Path, blocker: Blocker): Pipe[IO, Byte, Unit] = {
    fs2File.writeAll[IO](fileName, blocker)
  }
}
