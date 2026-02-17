package com.openclaw.healthuploader.analytics

import com.openclaw.healthuploader.HealthDailyRow
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Lightweight, deterministic "unit-test-like" verification for analytics logic.
 *
 * Not wired into any UI yet; you can call [runAll] from anywhere (e.g. a debug button/log)
 * to sanity-check behavior after refactors.
 */
object SleepInsightsAnalyticsDebugSamples {

  data class DebugCheck(
    val name: String,
    val ok: Boolean,
    val details: String? = null,
  )

  fun runAll(): List<DebugCheck> {
    val checks = mutableListOf<DebugCheck>()

    fun check(name: String, block: () -> Unit) {
      try {
        block()
        checks += DebugCheck(name = name, ok = true)
      } catch (t: Throwable) {
        checks += DebugCheck(name = name, ok = false, details = t.message ?: t.toString())
      }
    }

    check("stable_30d_activity_target_and_stage_pct") { stable30dActivityTargetAndStagePct() }
    check("unwrap_midnight_bedtime_stddev_small_and_stage_missing_ok") { unwrapMidnightBedtimeStddevAndStageMissing() }
    check("night_shift_correction_00_to_06_moves_to_previous_day") { nightShiftCorrectionMovesToPreviousDay() }
    check("linear_trend_slopes") { linearTrendSlopes() }

    return checks
  }

  fun runAllOrThrow() {
    val out = runAll()
    val failed = out.filter { !it.ok }
    if (failed.isNotEmpty()) {
      val msg = failed.joinToString(separator = "\n") { "FAIL ${it.name}: ${it.details}" }
      throw IllegalStateException(msg)
    }
  }

  private fun stable30dActivityTargetAndStagePct() {
    val zone = ZoneId.of("UTC")
    val anchor = LocalDate.parse("2026-02-17")
    val rows = sampleStable30Days(anchor = anchor, zone = zone)

    val result = SleepInsightsAnalytics.compute(rows = rows, zone = zone, targetSleepMin = 8 * 60)

    // Weekly (last 7 are all "high activity" days in this fixture)
    assertEquals("weekly.avgSleepMin", 450, result.weekly.avgSleepMin)
    assertEquals("weekly.avgEfficiencyPct", 94, result.weekly.avgEfficiencyPct) // 450/(450+30)=93.75 -> 94
    assertEquals("weekly.stageDays", 7, result.weekly.stageDays)
    assertEquals("weekly.stage.deepPct", 20, result.weekly.stagePercentages?.deepPct)
    assertEquals("weekly.stage.remPct", 24, result.weekly.stagePercentages?.remPct)
    assertEquals("weekly.stage.lightPct", 56, result.weekly.stagePercentages?.lightPct)
    assertEquals("weekly.regularityScore", 100, result.weekly.regularityScore)
    assertEquals("weekly.sleepDebtMin", 210, result.weekly.sleepDebtMin) // (480-450)*7
    assertApprox("weekly.durationSlope", 0.0, result.weekly.durationSlopeMinPerDay, tol = 1e-9)
    assertApprox("weekly.bedtimeSlope", 0.0, result.weekly.bedtimeSlopeMinPerDay, tol = 1e-9)
    assertApprox("weekly.wakeSlope", 0.0, result.weekly.wakeSlopeMinPerDay, tol = 1e-9)

    // Monthly
    assertEquals("monthly.avgSleepMin", 428, result.monthly.avgSleepMin) // (22*420 + 8*450)/30 = 428
    assertEquals("monthly.avgEfficiencyPct", 89, result.monthly.avgEfficiencyPct) // avg of 87.5% and 93.75% days
    assertEquals("monthly.stageDays", 30, result.monthly.stageDays)
    assertEquals("monthly.stage.deepPct", 19, result.monthly.stagePercentages?.deepPct)
    assertEquals("monthly.stage.remPct", 24, result.monthly.stagePercentages?.remPct)
    assertEquals("monthly.stage.lightPct", 57, result.monthly.stagePercentages?.lightPct)
    assertEquals("monthly.regularityScore", 100, result.monthly.regularityScore)

    // Recommendation
    val rec = result.recommendation
    assertEquals("rec.targetMin", 450, rec.targetMin)
    assertEquals("rec.targetRange", 420..480, rec.targetRange)
    assertEquals("rec.bedtimeWindow.start", 22 * 60 + 40, rec.bedtimeWindow?.startMinOfDay)
    assertEquals("rec.bedtimeWindow.end", 23 * 60 + 20, rec.bedtimeWindow?.endMinOfDay)
    assertEquals("rec.wakeWindow.start", 6 * 60 + 10, rec.wakeWindow?.startMinOfDay)
    assertEquals("rec.wakeWindow.end", 6 * 60 + 50, rec.wakeWindow?.endMinOfDay)
    assertEquals("rec.confidence", SleepInsightsAnalytics.ConfidenceLevel.HIGH, rec.confidence)
  }

  private fun unwrapMidnightBedtimeStddevAndStageMissing() {
    val zone = ZoneId.of("UTC")
    val rows = listOf(
      mkRow(
        day = LocalDate.parse("2026-02-01"),
        zone = zone,
        bedtime = LocalTime.of(23, 50),
        wake = LocalTime.of(7, 0),
        sleepMin = 420,
        awakeMin = 60,
        stages = Stages(deep = 80, rem = 100, light = 240),
      ),
      // Bedtime just after midnight -> should be unwrapped close to ~24:10, not 00:10.
      // Note: day is set to 2026-02-03 so that night-shift correction maps it to 2026-02-02 (no collision).
      mkRow(
        day = LocalDate.parse("2026-02-03"),
        zone = zone,
        bedtime = LocalTime.of(0, 10),
        wake = LocalTime.of(7, 0),
        sleepMin = 410,
        awakeMin = 10,
        stages = null, // missing stages should not crash; also makes stagePercentages null (stageDays < 3)
      ),
      mkRow(
        day = LocalDate.parse("2026-02-03"),
        zone = zone,
        bedtime = LocalTime.of(23, 55),
        wake = LocalTime.of(7, 0),
        sleepMin = 430,
        awakeMin = 50,
        stages = Stages(deep = 85, rem = 105, light = 240),
      ),
    )

    val result = SleepInsightsAnalytics.compute(rows = rows, zone = zone, targetSleepMin = 8 * 60)
    val std = result.weekly.bedtimeStdDevMin ?: error("bedtimeStdDevMin is null")
    if (std > 20.0) error("Expected bedtime stddev <= 20min, got $std")

    assertEquals("weekly.stagePercentages", null, result.weekly.stagePercentages)
  }

  private fun nightShiftCorrectionMovesToPreviousDay() {
    val zone = ZoneId.of("UTC")
    val rows = listOf(
      mkRow(
        day = LocalDate.parse("2026-02-10"),
        zone = zone,
        bedtime = LocalTime.of(1, 0),
        wake = LocalTime.of(9, 0),
        sleepMin = 480,
        awakeMin = 0,
        stages = null,
      ),
    )

    val points = SleepInsightsAnalytics.buildDailyPoints(rows = rows, zone = zone)
    assertEquals("points.size", 1, points.size)
    assertEquals("effectiveDay", LocalDate.parse("2026-02-09"), points[0].day)
  }

  private fun linearTrendSlopes() {
    val zone = ZoneId.of("UTC")
    val anchor = LocalDate.parse("2026-02-17")
    val start = anchor.minusDays(6)

    val rows = (0 until 7).map { i ->
      val day = start.plusDays(i.toLong())
      val bedtime = LocalTime.of(22, 0).plusMinutes((i * 5).toLong())
      val wake = bedtime.plusHours(8) // wake time shifts in lockstep
      mkRow(
        day = day,
        zone = zone,
        bedtime = bedtime,
        wake = wake,
        sleepMin = 400 + i * 10,
        awakeMin = 0,
        stages = null,
      )
    }

    val result = SleepInsightsAnalytics.compute(rows = rows, zone = zone, targetSleepMin = 8 * 60)
    assertApprox("weekly.durationSlope", 10.0, result.weekly.durationSlopeMinPerDay, tol = 1e-9)
    assertApprox("weekly.bedtimeSlope", 5.0, result.weekly.bedtimeSlopeMinPerDay, tol = 1e-9)
    assertApprox("weekly.wakeSlope", 5.0, result.weekly.wakeSlopeMinPerDay, tol = 1e-9)
  }

  private data class Stages(
    val deep: Int,
    val rem: Int,
    val light: Int,
  )

  private fun sampleStable30Days(anchor: LocalDate, zone: ZoneId): List<HealthDailyRow> {
    val start = anchor.minusDays(29)
    return (0 until 30).map { idx ->
      val day = start.plusDays(idx.toLong())
      val isHighActivityDay = idx >= 22 // last 8 days (top 25% by steps in this fixture)

      val sleepMin = if (isHighActivityDay) 450 else 420
      val awakeMin = 480 - sleepMin // keep a constant 8h "sleep window" for stable bedtime/wake
      val stages = if (isHighActivityDay) Stages(deep = 90, rem = 110, light = 250) else Stages(deep = 80, rem = 100, light = 240)

      mkRow(
        day = day,
        zone = zone,
        bedtime = LocalTime.of(23, 0),
        wake = LocalTime.of(7, 0),
        sleepMin = sleepMin,
        awakeMin = awakeMin,
        stages = stages,
        steps = (10_000L + idx.toLong()),
      )
    }
  }

  private fun mkRow(
    day: LocalDate,
    zone: ZoneId,
    bedtime: LocalTime,
    wake: LocalTime,
    sleepMin: Int,
    awakeMin: Int,
    stages: Stages?,
    steps: Long? = null,
  ): HealthDailyRow {
    val startZ = ZonedDateTime.of(day, bedtime, zone)
    val endZ = ZonedDateTime.of(
      if (wake >= bedtime) day else day.plusDays(1),
      wake,
      zone
    )

    return HealthDailyRow(
      day = day.toString(),
      sleepStart = startZ.toInstant().toString(),
      sleepEnd = endZ.toInstant().toString(),
      sleepMinutes = sleepMin,
      sleepAwakeMinutes = awakeMin,
      sleepLightMinutes = stages?.light,
      sleepDeepMinutes = stages?.deep,
      sleepRemMinutes = stages?.rem,
      sleepScore = null,
      sleepAvgHr = null,
      sleepSpo2 = null,
      sleepDurationMinutes = null,
      steps = steps,
      distanceKm = null,
      activeCalories = null,
      workoutsCount = null,
    )
  }

  private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) {
      error("$name expected=$expected actual=$actual")
    }
  }

  private fun assertApprox(name: String, expected: Double, actual: Double?, tol: Double) {
    if (actual == null) error("$name expected=$expected actual=null")
    if (abs(expected - actual) > tol) {
      error("$name expected=$expected actual=$actual tol=$tol")
    }
  }
}

