package io.github.dlinov.speeding.model.parser

import java.time.{LocalDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.UUID

import io.github.dlinov.speeding.model.Fine
import org.slf4j.LoggerFactory

import scala.io.Source
import scala.xml.parsing.XhtmlParser

object ResponseParser {
  private val logger = LoggerFactory.getLogger(getClass)

  private val dtFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy H:mm:ss")

  def parse(resp: String): Either[ParsingError, Seq[Fine]] = {
    if (resp.startsWith("<html>")) {
      Left(err("Whole page response", resp))
    } else {
      val unescapedResp = StringContext.treatEscapes(resp)
      if (unescapedResp.startsWith("\"") && unescapedResp.endsWith("\"")) {
        // valid html response
        // wrapping to tag because it's requirement from XhtmlParser to have single enclosing tag
        val htmlResp = s"<span>${unescapedResp.substring(1, unescapedResp.length - 1)}</span>"

        val nodes = XhtmlParser(Source.fromString(htmlResp))
        val maybeNoFines = nodes.\("h2").headOption
        maybeNoFines match {
          case Some(n) if n.text.contains("информация не найдена") ⇒
            logger.debug("No fines, congratulations!")
            Right(Seq.empty)
          case _ ⇒
            val finesTableRows = nodes.\("table").\("tr")
            finesTableRows
              .headOption
              .fold[Either[ParsingError, Seq[Fine]]] {
                Left(err("No table with files found", unescapedResp))
              } { _ ⇒
                // TODO: dynamically find column indices from header row
                Right(finesTableRows.tail.map(row ⇒ {
                  val cells = row.\("td")
                  val id = cells(4).text.toLong
                  val time = LocalDateTime
                    .from(dtFormat.parse(cells(3).text))
                    .toInstant(ZoneOffset.of("Europe/Minsk"))
                  Fine(id, time)
                }))
              }
        }
      } else {
        // most probably mailformed json error
        Left(err("Unexpected response", resp))
      }
    }
  }

  private def err(msg: String, resp: String): ParsingError = {
    val errId = UUID.randomUUID()
    logger.warn(s"Parsing error $errId. $msg. Full response is `$resp`")
    ParsingError(s"$msg, see error $errId in logs for more information")
  }
}
