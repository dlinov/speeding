package io.github.dlinov.speeding.model

final case class DriverInfo(
    id: Long,
    fullName: String,
    licenseSeries: String,
    licenseNumber: String)
