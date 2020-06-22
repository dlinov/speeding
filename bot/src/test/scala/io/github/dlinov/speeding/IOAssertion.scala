package io.github.dlinov.speeding

import cats.effect.IO

object IOAssertion {
  def apply[A](ioa: IO[A]): Unit = ioa./*void*/map(_ => ()).unsafeRunSync()
}
