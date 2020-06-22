package io.github.dlinov.speeding.dao

import cats.effect.{Blocker, IO}
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import io.github.dlinov.speeding.PureTestSuite
import io.github.dlinov.speeding.model.DriverInfo
import org.flywaydb.core.Flyway
import org.scalatest.{Assertion, BeforeAndAfterAll, EitherValues, OptionValues}

class DaoSpec extends PureTestSuite with EitherValues with OptionValues with BeforeAndAfterAll {
  private val driverId = 9876L
  private val driverInfo = DriverInfo(
    id = driverId,
    fullName = "ФФФ ИИИ ООО",
    licenseSeries = "МАА",
    licenseNumber = "1234567"
  )
  private val userId = 1234L
  private val connectionEC = ExecutionContexts.fixedThreadPool[IO](4)
  private val blocker = Blocker[IO]
  private val dbUri =
    "jdbc:h2:mem:speeding;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
  private val dbUser = ""
  private val dbPassword = ""

  override def beforeAll(): Unit = {
    val fw = Flyway.configure().dataSource(dbUri, dbUser, dbPassword).load()
    fw.migrate()
  }

  def check[A](assertableAction: PostgresDao => IO[Assertion]): IO[Assertion] = {
    val xaResource = for {
      ce <- connectionEC
      be <- blocker
      xa <-
        HikariTransactor
          .newHikariTransactor[IO]("org.postgresql.Driver", dbUri, dbUser, dbPassword, ce, be)
    } yield xa
    xaResource.use[IO, Assertion] { xa =>
      val dao = new PostgresDao(xa)
      assertableAction(dao)
    }
  }

  spec("Postgres dao should not find non-existing user") {
    check { dao =>
      dao
        .findDriver(driverId)
        .map(_.right.value.isEmpty)
        .map(assert(_))
    }
  }
  spec("Postgres dao should insert new driver info") {
    check { dao =>
      dao
        .updateDriver(driverId, driverInfo)
        .map(_.right.value == driverInfo)
        .map(assert(_))
    }
  }
}
