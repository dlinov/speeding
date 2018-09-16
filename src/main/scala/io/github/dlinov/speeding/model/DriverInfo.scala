package io.github.dlinov.speeding.model
import java.util.Locale

final case class DriverInfo(
                             id: Long,
                             fullName: String,
                             licenseSeries: String,
                             licenseNumber: String,
                             lang: String) {
  val locale: Locale = Locale.forLanguageTag(lang)
}
