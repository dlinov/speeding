package io.github.dlinov.speeding

import cats.effect._
import org.scalactic.source.Position
import org.scalatest.Assertion
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import scala.concurrent.ExecutionContext

trait PureTestSuite extends AnyFunSuite with ScalaCheckDrivenPropertyChecks /*with CatsEquality*/ {

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO]     = IO.timer(ExecutionContext.global)

  def spec(testName: String) (f: => IO[Assertion])
          (implicit pos: Position): Unit = test(testName)(IOAssertion(IO.suspend(f)))
}
