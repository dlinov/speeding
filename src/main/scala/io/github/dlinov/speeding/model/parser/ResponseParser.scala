package io.github.dlinov.speeding.model.parser

import java.time.{Instant, LocalDateTime, ZonedDateTime}
import java.time.format.DateTimeFormatter
import java.util.UUID

import io.github.dlinov.speeding.model.{Constants, Fine}
import org.apache.commons.text.StringEscapeUtils
import org.slf4j.LoggerFactory

import scala.io.Source
import scala.xml.parsing.XhtmlParser

object ResponseParser {
  private val logger = LoggerFactory.getLogger(getClass)

  private val dtFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy H:mm:ss")

  def parse(driverId: Long, resp: String): Either[ParsingError, Seq[Fine]] =
    if (resp.startsWith("<html>")) {
      Left(err("Whole page response", resp))
    } else {
      if (resp.startsWith("\"") && resp.endsWith("\"")) {
        val tmp = resp.substring(1, resp.length - 1)

        // valid html response
        // wrapping in a tag because it's requirement from XhtmlParser to have single enclosing tag
        val htmlResp = s"<span>${StringEscapeUtils.unescapeJava(tmp)}</span>"

        val doc = XhtmlParser(Source.fromString(htmlResp))
        val maybeNoFines = doc.\("h2").headOption
        maybeNoFines match {
          case Some(n) if n.text.contains("информация не найдена") ⇒
            logger.debug("No fines, congratulations!")
            Right(Seq.empty)
          case _ ⇒
            val finesTableRows = doc.\("table").\("tr")
            if (finesTableRows.isEmpty) {
              Left(err("No table with files found", htmlResp))
            } else {
              // TODO: dynamically find column indices from header row
              Right(finesTableRows.tail.map(row ⇒ {
                val cells = row.\("td")
                val id = cells(4).text.toLong
                val timestamp = cells(3).text.asTimestamp
                Fine(id, driverId, timestamp)
              }))
            }
        }
      } else {
        // most probably malformed json error
        Left(err("Unexpected response", resp))
      }
    }

  private def err(msg: String, resp: String): ParsingError = {
    val errId = UUID.randomUUID()
    logger.warn(s"Parsing error $errId. $msg. Full response is `$resp`")
    ParsingError(s"$msg, see error $errId in logs for more information")
  }

  implicit class ResponseTimeConverter(val input: String) extends AnyVal {
    def asTimestamp: Instant =
      ZonedDateTime.of(LocalDateTime.from(dtFormat.parse(input)), Constants.TZ).toInstant
  }
}
