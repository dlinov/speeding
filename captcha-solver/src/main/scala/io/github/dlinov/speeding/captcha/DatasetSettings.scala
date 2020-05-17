package io.github.dlinov.speeding.captcha

import java.nio.file.{Path, Paths}

trait DatasetSettings {
  import DatasetSettings._

  def digitsPath: Path = DigitsPath

  def signsPath: Path = SignsPath

  def masksPath: Path = MasksPath
}

object DatasetSettings {
  private val BaseDir = Paths.get("captcha-solver", "datasets")
  private val DigitsPath = BaseDir.resolve("digits")
  private val SignsPath: Path = BaseDir.resolve("signs")
  private val MasksPath: Path = Paths.get("captcha-solver", "src", "main", "resources", "masks")
}
