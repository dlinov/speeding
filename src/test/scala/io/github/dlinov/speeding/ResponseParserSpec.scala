package io.github.dlinov.speeding

import java.time.ZonedDateTime

import io.github.dlinov.speeding.model.{Constants, Fine}
import io.github.dlinov.speeding.model.parser.ResponseParser
import org.scalatest.{EitherValues, MustMatchers, WordSpec}

class ResponseParserSpec extends WordSpec with MustMatchers with EitherValues {

  private val driverId = 9876

  "ResponseParser" should {
    "parse response with no fines" in {
      val input =
        """"\u003ch2\u003eПо заданным критериям поиска информация не найдена\u003c/h2\u003e""""
      ResponseParser.parse(driverId, input).right.value must be(Seq.empty)
    }

    "parse response with fines" in {
      val input =
        """"\u003ctable class=\"ii\" cellspacing=\"0\" cellpadding=\"2\" border=\"1\"\u003e\r\n  \u003ctr style=\"background-color: silver\"\u003e\r\n    \u003ctd\u003eФамилия, Имя, Отчество\u003c/td\u003e\r\n    \u003ctd\u003eСерия\u003c/td\u003e\r\n    \u003ctd\u003eСвид. о регистрации\u003c/td\u003e\r\n    \u003ctd\u003eДата и время правонарушения\u003c/td\u003e\r\n    \u003ctd\u003eРег. № правонарушения\u003c/td\u003e\r\n  \u003c/tr\u003e\r\n  \u003ctr\u003e\r\n    \u003ctd\u003eФ И О\u003c/td\u003e\r\n    \u003ctd\u003eMAA\u003c/td\u003e\r\n    \u003ctd\u003e1870000\u003c/td\u003e\r\n    \u003ctd\u003e01.08.2018 1:11:00\u003c/td\u003e\r\n    \u003ctd\u003e18123400789\u003c/td\u003e\r\n  \u003c/tr\u003e\r\n\u003c/table\u003e\r\n\u003cdiv\u003e\u003cb\u003eДата и время последнего обновления данных 05.09.2018 10:02:35.\u003c/b\u003e\u003c/div\u003e\r\n\u003cp\u003eШтраф за \r\nправонарушение можно оплатить в любом банке, подключенном к системе \"Расчет\" (ЕРИП). \r\nОплату можно совершить в платежно-справочном терминале, интернет-банкинге, \r\nбанкомате, кассе банка и других пунктах банковского обслуживания. Более подробная информация о перечне пунктов \r\nбанковского обслуживания, задействованных в системе \"Расчет\" (ЕРИП), размещена \r\nна сайте\r\n\u003ca target=\"_blank\" style=\"color: blue\" href=\"http://raschet.by/\"\u003e\r\nwww.raschet.by\u003c/a\u003e в разделе \"Плательщик\". В случае возникновения вопросов по \r\nсовершению оплаты обращайтесь к сотрудникам банка.\u003c/p\u003e\r\n\u003cp\u003eДля оплаты правонарушения Вам, либо сотруднику банка необходимо:\u003c/p\u003e\r\n\u003col\u003e\r\n  \u003cli\u003eВ системе \"Расчет\" (ЕРИП) последовательно перейти в разделы \"МВД\" - \"ГАИ - \r\nфотофиксация\", после чего выбрать услугу \"Скоростной режим\" (номер услуги 381141);\u003c/li\u003e\r\n  \u003cli\u003eВвести регистрационный номер правонарушения;\u003c/li\u003e\r\n  \u003cli\u003eСверить данные о правонарушении (фамилию, имя, отчество владельца транспортного средства \r\nи сумму штрафа);\u003c/li\u003e\r\n  \u003cli\u003eСовершить оплату.\u003c/li\u003e\r\n\u003c/ol\u003e\r\n\u003cp\u003eВ случае оплаты в РУП \"БелПочта\" квитанцию об оплате необходимо отправить заказным \r\nписьмом по адресу: 220030, г. Минск, ул. Красноармейская, д. 21. Отдел по обеспечению деятельности Единой системы фотофиксации нарушений скоростного режима МВД Республики Беларусь (ООД ЕСФНСР МВД). Счет для оплаты в РУП \"БелПочта\": \r\n3602916010009, код платежа 05104.\u003c/p\u003e""""

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
                     |</html>"""
      ResponseParser.parse(driverId, input1).isLeft must be(true)
      ResponseParser.parse(driverId, input2).isLeft must be(true)
    }
  }
}
