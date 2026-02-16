package com.openclaw.healthuploader

data class HealthDailyRow(
  val day: String,
  val steps: Long?,
  val distanceKm: Double?,
  val activeCalories: Double?,
  val workoutsCount: Int?,
  val sleepDurationMinutes: Int?,
)
