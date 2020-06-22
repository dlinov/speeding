package io.github.dlinov.speeding.model.parser

import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime

import io.github.dlinov.speeding.model.{Constants, Fine}
import org.scalatest.EitherValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.io.Source

class ResponseParserSpec extends AnyWordSpec with Matchers with EitherValues {

  private val driverId = 9876

  "ResponseParser" should {
    "parse response with no fines" in {
      val input = readResourceAsText("/response-no-fines.txt")
      ResponseParser.parse(driverId, input).right.value must be(Seq.empty)
    }

    "parse response with fines" in {
      val input = readResourceAsText("/response-with-fines.txt")
      val expectedFine1 = Fine(
        id = 18123400789L,
        driverId = driverId,
        timestamp = ZonedDateTime.of(2018, 8, 1, 1, 11, 0, 0, Constants.TZ).toInstant)
      ResponseParser.parse(driverId, input).right.value must be(Seq(expectedFine1))
    }

    "return error for unexpected response" in {
      val input1 = """{"Message":"There was an error processing the request.","StackTrace":"","ExceptionType":""}"""
      val input2 = """<html>
                     |    <head>
                     |        <title>Runtime Error</title>
                     |    </head>
                     |
                     |    <body bgcolor="white">
                     |            <span><H1>Server Error in '/' Application.<hr width=100% size=1 color=silver></H1>
                     |            <h2> <i>Runtime Error</i> </h2></span>
                     |            <font face="Arial, Helvetica, Geneva, SunSans-Regular, sans-serif ">
                     |            <b> Description: </b>An application error occurred on the server. The current custom error settings for this application prevent the details of the application error from being viewed remotely (for security reasons). It could, however, be viewed by browsers running on the local server machine.
                     |            <br><br>
                     |            <b>Details:</b> To enable the details of this specific error message to be viewable on remote machines, please create a &lt;customErrors&gt; tag within a &quot;web.config&quot; configuration file located in the root directory of the current web application. This &lt;customErrors&gt; tag should then have its &quot;mode&quot; attribute set to &quot;Off&quot;.<br><br>
                     |            <table width=100% bgcolor="#ffffcc">
                     |               <tr><td></td></tr>
                     |            </table>
                     |            <br>
                     |    </body>
                     |</html>""".stripMargin
      ResponseParser.parse(driverId, input1).isLeft must be(true)
      ResponseParser.parse(driverId, input2).isLeft must be(true)
    }
  }

  private def readResourceAsText(resource: String) = {
    Source.fromFile(getClass.getResource(resource).toURI, StandardCharsets.UTF_8.name).mkString
  }
}
