package com.openclaw.healthuploader

data class HealthDailyRow(
  val day: String,
  // Sleep (v2 fields; nullable for backwards-compatible dashboard reads)
  val sleepStart: String?,
  val sleepEnd: String?,
  val sleepMinutes: Int?,
  val sleepAwakeMinutes: Int?,
  val sleepLightMinutes: Int?,
  val sleepDeepMinutes: Int?,
  val sleepRemMinutes: Int?,
  val sleepScore: Double?,
  val sleepAvgHr: Double?,
  val sleepSpo2: Double?,
  // Sleep (v1 field kept for backwards compatibility)
  val sleepDurationMinutes: Int?,
  // Activity (lower priority)
  val steps: Long?,
  val distanceKm: Double?,
  val activeCalories: Double?,
  val workoutsCount: Int?,
)
