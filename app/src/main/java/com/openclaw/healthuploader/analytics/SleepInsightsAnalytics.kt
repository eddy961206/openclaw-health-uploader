package com.openclaw.healthuploader.analytics

import com.openclaw.healthuploader.HealthDailyRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Local-only sleep insights computed from already-fetched `health_daily` rows.
 *
 * Notes:
 * - Works without backend migrations by using `sleep_minutes` when present, else `sleep_duration_minutes`.
 * - Uses local device timezone for time-of-day computations.
 * - Applies a "night shift" correction: if bedtime is between 00:00~06:00, attribute it to the previous day.
 */
object SleepInsightsAnalytics {

  data class DailyPoint(
    val day: LocalDate,
    val sleepMin: Int?,
    val awakeMin: Int?,
    val bedtimeMinOfDay: Int?, // 0..1439 (local)
    val wakeMinOfDay: Int?, // 0..1439 (local)
    val deepMin: Int?,
    val remMin: Int?,
    val lightMin: Int?,
    val stageAvailable: Boolean,
    val steps: Long?,
    val activeCalories: Double?,
  )

  enum class ConfidenceLevel { HIGH, MID, LOW }

  data class StagePercentages(
    val deepPct: Int, // 0..100
    val remPct: Int, // 0..100
    val lightPct: Int, // 0..100
  )

  data class TimeWindow(
    val startMinOfDay: Int, // 0..1439
    val endMinOfDay: Int, // 0..1439 (can wrap when start > end)
  )

  data class PeriodSummary(
    val days: Int,
    val daysWithSleep: Int,
    val avgSleepMin: Int?,
    val avgEfficiencyPct: Int?,
    val stageDays: Int,
    val stagePercentages: StagePercentages?, // null when not enough stage data
    val regularityScore: Int?, // 0..100 (null when not enough data)
    val bedtimeStdDevMin: Double?,
    val wakeStdDevMin: Double?,
    val sleepDebtMin: Int?, // only meaningful when target is provided (weekly)
    val durationSlopeMinPerDay: Double?,
    val bedtimeSlopeMinPerDay: Double?,
    val wakeSlopeMinPerDay: Double?,
  )

  data class OptimalSleepRecommendation(
    val targetMin: Int?, // null when not enough data
    val targetRange: IntRange?, // [min..max] minutes, already clamped
    val bedtimeWindow: TimeWindow?,
    val wakeWindow: TimeWindow?,
    val confidence: ConfidenceLevel,
    val whyShort: String,
    val whyLong: String?,
  )

  data class InsightsResult(
    val weekly: PeriodSummary,
    val monthly: PeriodSummary,
    val recommendation: OptimalSleepRecommendation,
  )

  fun compute(
    rows: List<HealthDailyRow>,
    zone: ZoneId,
    targetSleepMin: Int,
  ): InsightsResult {
    val pointsAll = buildDailyPoints(rows, zone)
    if (pointsAll.isEmpty()) {
      val emptyWeekly = PeriodSummary(
        days = 7,
        daysWithSleep = 0,
        avgSleepMin = null,
        avgEfficiencyPct = null,
        stageDays = 0,
        stagePercentages = null,
        regularityScore = null,
        bedtimeStdDevMin = null,
        wakeStdDevMin = null,
        sleepDebtMin = null,
        durationSlopeMinPerDay = null,
        bedtimeSlopeMinPerDay = null,
        wakeSlopeMinPerDay = null,
      )
      val emptyMonthly = emptyWeekly.copy(days = 30)
      return InsightsResult(
        weekly = emptyWeekly,
        monthly = emptyMonthly,
        recommendation = OptimalSleepRecommendation(
          targetMin = null,
          targetRange = null,
          bedtimeWindow = null,
          wakeWindow = null,
          confidence = ConfidenceLevel.LOW,
          whyShort = "분석을 위한 데이터가 부족해. (대시보드 동기화를 먼저 해줘)",
          whyLong = null,
        ),
      )
    }

    val anchor = pointsAll.maxOf { it.day }
    val last7 = sliceCalendarDays(pointsAll, anchor, days = 7)
    val last30 = sliceCalendarDays(pointsAll, anchor, days = 30)
    val last21 = sliceCalendarDays(pointsAll, anchor, days = 21)

    val weekly = computePeriodSummary(last7, days = 7, targetSleepMin = targetSleepMin)
    val monthly = computePeriodSummary(last30, days = 30, targetSleepMin = null)

    val confidence = computeConfidence(last7 = last7, last21 = last21)
    val recommendation = computeRecommendation(
      last7 = last7,
      last30 = last30,
      confidence = confidence,
    )

    return InsightsResult(
      weekly = weekly,
      monthly = monthly,
      recommendation = recommendation,
    )
  }

  fun buildDailyPoints(rows: List<HealthDailyRow>, zone: ZoneId): List<DailyPoint> {
    // 1) Convert rows -> candidate points (with night-shift day correction).
    val candidates = mutableListOf<DailyPoint>()
    for (r in rows) {
      val rowDay = parseDay(r.day) ?: continue
      val startZ = parseZonedDateTime(r.sleepStart, zone)
      val endZ = parseZonedDateTime(r.sleepEnd, zone)

      val effectiveDay =
        if (startZ != null && startZ.hour in 0..6) rowDay.minusDays(1) else rowDay

      val sleepMin = r.sleepMinutes ?: r.sleepDurationMinutes
      val bedtimeMin = startZ?.let { minutesOfDay(it) }
      val wakeMin = endZ?.let { minutesOfDay(it) }

      val stageAvailable = (r.sleepDeepMinutes != null && r.sleepRemMinutes != null && r.sleepLightMinutes != null)

      candidates.add(
        DailyPoint(
          day = effectiveDay,
          sleepMin = sleepMin,
          awakeMin = r.sleepAwakeMinutes,
          bedtimeMinOfDay = bedtimeMin,
          wakeMinOfDay = wakeMin,
          deepMin = r.sleepDeepMinutes,
          remMin = r.sleepRemMinutes,
          lightMin = r.sleepLightMinutes,
          stageAvailable = stageAvailable,
          steps = r.steps,
          activeCalories = r.activeCalories,
        )
      )
    }

    if (candidates.isEmpty()) return emptyList()

    // 2) De-duplicate by day: keep the "most complete" point per effective day.
    val byDay = mutableMapOf<LocalDate, DailyPoint>()
    for (p in candidates) {
      val prev = byDay[p.day]
      if (prev == null || pointCompletenessScore(p) > pointCompletenessScore(prev)) {
        byDay[p.day] = p
      }
    }

    // 3) Return chronological.
    return byDay.values.sortedBy { it.day }
  }

  private fun computePeriodSummary(
    points: List<DailyPoint>,
    days: Int,
    targetSleepMin: Int?,
  ): PeriodSummary {
    val sleepVals = points.mapNotNull { it.sleepMin?.takeIf { v -> v > 0 } }
    val daysWithSleep = sleepVals.size

    val avgSleepMin = if (sleepVals.isNotEmpty()) sleepVals.average().roundToInt() else null

    val efficiencyVals = points.mapNotNull { p ->
      val s = p.sleepMin
      val a = p.awakeMin
      if (s != null && s > 0 && a != null && a >= 0) (s.toDouble() / (s + a).toDouble()) * 100.0 else null
    }
    val avgEfficiencyPct = if (efficiencyVals.isNotEmpty()) efficiencyVals.average().roundToInt() else null

    val stagePoints = points.filter { p ->
      val s = p.sleepMin
      val deep = p.deepMin
      val rem = p.remMin
      val light = p.lightMin
      s != null && s > 0 &&
        deep != null && deep >= 0 &&
        rem != null && rem >= 0 &&
        light != null && light >= 0
    }
    val stageDays = stagePoints.size
    val stagePercentages = if (stageDays >= 3) computeStagePercentages(stagePoints) else null

    val bedtimeTimes = points.mapNotNull { it.bedtimeMinOfDay }
    val wakeTimes = points.mapNotNull { it.wakeMinOfDay }

    val bedtimeStats = if (bedtimeTimes.size >= 3) unwrapMinStdDev(bedtimeTimes) else null
    val wakeStats = if (wakeTimes.size >= 3) unwrapMinStdDev(wakeTimes) else null

    val regularityScore = run {
      val b = bedtimeStats?.stdDevMin
      val w = wakeStats?.stdDevMin
      if (b == null || w == null) null
      else {
        val combined = (b + w) / 2.0
        // Heuristic: 0 min stddev => 100점, 120분 stddev => 40점, 200분 => 0점.
        (100.0 - combined * 0.5).roundToInt().coerceIn(0, 100)
      }
    }

    val debtMin = if (targetSleepMin != null) {
      var sum = 0
      for (p in points) {
        val s = p.sleepMin ?: continue
        val deficit = targetSleepMin - s
        if (deficit > 0) sum += deficit
      }
      sum
    } else null

    val durationSlope = computeSlope(points.map { it.sleepMin?.toDouble() })

    val bedtimeSlope = computeSlopeForTimes(points) { it.bedtimeMinOfDay }
    val wakeSlope = computeSlopeForTimes(points) { it.wakeMinOfDay }

    return PeriodSummary(
      days = days,
      daysWithSleep = daysWithSleep,
      avgSleepMin = avgSleepMin,
      avgEfficiencyPct = avgEfficiencyPct,
      stageDays = stageDays,
      stagePercentages = stagePercentages,
      regularityScore = regularityScore,
      bedtimeStdDevMin = bedtimeStats?.stdDevMin,
      wakeStdDevMin = wakeStats?.stdDevMin,
      sleepDebtMin = debtMin,
      durationSlopeMinPerDay = durationSlope,
      bedtimeSlopeMinPerDay = bedtimeSlope,
      wakeSlopeMinPerDay = wakeSlope,
    )
  }

  private fun computeConfidence(
    last7: List<DailyPoint>,
    last21: List<DailyPoint>,
  ): ConfidenceLevel {
    val days21 = last21.count { (it.sleepMin ?: 0) > 0 }
    val sleepDays21 = last21.filter { (it.sleepMin ?: 0) > 0 }
    val stageDays21 = sleepDays21.count { it.stageAvailable }
    val stageCoverage = if (sleepDays21.isEmpty()) 0.0 else stageDays21.toDouble() / sleepDays21.size.toDouble()

    if (days21 >= 18 && stageCoverage >= 0.80) return ConfidenceLevel.HIGH

    val days7 = last7.count { (it.sleepMin ?: 0) > 0 }
    if (days7 >= 5) return ConfidenceLevel.MID

    return ConfidenceLevel.LOW
  }

  private fun computeRecommendation(
    last7: List<DailyPoint>,
    last30: List<DailyPoint>,
    confidence: ConfidenceLevel,
  ): OptimalSleepRecommendation {
    val days7 = last7.count { (it.sleepMin ?: 0) > 0 }
    if (days7 < 3) {
      return OptimalSleepRecommendation(
        targetMin = null,
        targetRange = null,
        bedtimeWindow = null,
        wakeWindow = null,
        confidence = ConfidenceLevel.LOW,
        whyShort = "분석을 위한 데이터가 부족해. (최소 3일의 수면 기록이 필요해)",
        whyLong = null,
      )
    }

    val sleep30 = last30.mapNotNull { it.sleepMin?.takeIf { v -> v > 0 } }
    if (sleep30.size < 3) {
      return OptimalSleepRecommendation(
        targetMin = null,
        targetRange = null,
        bedtimeWindow = null,
        wakeWindow = null,
        confidence = ConfidenceLevel.LOW,
        whyShort = "최근 30일 수면 데이터가 부족해. (대시보드 동기화를 확인해줘)",
        whyLong = null,
      )
    }

    val targetFromActivity = computeTargetSleepFromActivity(last30)
    val targetMinRaw = targetFromActivity ?: medianInt(sleep30)
    val targetMin = clampInt(targetMinRaw, min = 6 * 60, max = 9 * 60)

    val rangeMin = clampInt(targetMin - 30, min = 6 * 60, max = 9 * 60)
    val rangeMax = clampInt(targetMin + 30, min = 6 * 60, max = 9 * 60)
    val targetRange = rangeMin..rangeMax

    val bedtimeCenter = computeBedtimeCenter(last7)
    val bedtimeWindow = bedtimeCenter?.let { center -> windowAround(center, halfWindowMin = 20) }

    val wakeCenter = if (bedtimeCenter != null) clampMinOfDay(bedtimeCenter + targetMin) else null
    val wakeWindow = wakeCenter?.let { center -> windowAround(center, halfWindowMin = 20) }

    val whyShort = buildString {
      if (targetFromActivity != null) {
        append("최근 30일 중 활동량 상위 25%인 날에 평균 ")
        append(formatMinutesKo(targetFromActivity))
        append(" 정도 잤어. 그래서 목표를 ")
        append(formatMinutesKo(targetMin))
        append("으로 잡았어.")
      } else {
        append("활동 데이터가 부족해서 최근 30일 수면 중앙값(메디안) ")
        append(formatMinutesKo(medianInt(sleep30)))
        append("을 기준으로 목표를 ")
        append(formatMinutesKo(targetMin))
        append("으로 잡았어.")
      }
    }

    val whyLong = buildString {
      append("데이터: 최근 7일 ")
      append(days7)
      append("일, 최근 30일 ")
      append(sleep30.size)
      append("일 기준. ")
      if (bedtimeCenter != null) {
        append("취침 윈도우는 최근 7일 중 패턴이 가장 일정했던 날들을 기준으로 평균 취침 시각 ±20분으로 잡았어. ")
      } else {
        append("최근 7일 취침/기상 시간이 부족해서 취침 윈도우는 계산을 생략했어. ")
      }
      append("신뢰도는 데이터 일수/수면 단계 포함률로 계산했어.")
    }

    return OptimalSleepRecommendation(
      targetMin = targetMin,
      targetRange = targetRange,
      bedtimeWindow = bedtimeWindow,
      wakeWindow = wakeWindow,
      confidence = confidence,
      whyShort = whyShort,
      whyLong = whyLong,
    )
  }

  private fun computeTargetSleepFromActivity(last30: List<DailyPoint>): Int? {
    val withSleep = last30.filter { (it.sleepMin ?: 0) > 0 }
    if (withSleep.size < 7) return null

    val stepsDays = withSleep.count { it.steps != null }
    val calDays = withSleep.count { it.activeCalories != null }

    val metric: (DailyPoint) -> Double? = when {
      stepsDays >= 10 -> { p -> p.steps?.toDouble() }
      calDays >= 10 -> { p -> p.activeCalories }
      else -> return null
    }

    val scored = withSleep.mapNotNull { p ->
      val m = metric(p) ?: return@mapNotNull null
      val s = p.sleepMin ?: return@mapNotNull null
      Pair(m, s)
    }
    if (scored.size < 7) return null

    val topCount = (ceil(scored.size * 0.25)).toInt().coerceAtLeast(1)
    val top = scored.sortedByDescending { it.first }.take(topCount)
    if (top.size < 3) return null

    return top.map { it.second }.average().roundToInt()
  }

  private fun computeBedtimeCenter(last7: List<DailyPoint>): Int? {
    val pairs = last7.mapNotNull { p ->
      val b = p.bedtimeMinOfDay ?: return@mapNotNull null
      val w = p.wakeMinOfDay ?: return@mapNotNull null
      Pair(b, w)
    }
    if (pairs.size < 3) return null

    val bedtimes = pairs.map { it.first }
    val wakes = pairs.map { it.second }

    val bedUnwrap = unwrapMinStdDev(bedtimes)
    val wakeUnwrap = unwrapMinStdDev(wakes)

    val bedMedian = medianInt(bedUnwrap.unwrapped)
    val wakeMedian = medianInt(wakeUnwrap.unwrapped)

    data class Row(val bed: Int, val wake: Int, val dev: Int)
    val ranked = bedUnwrap.unwrapped.indices.map { idx ->
      val b = bedUnwrap.unwrapped[idx]
      val w = wakeUnwrap.unwrapped[idx]
      Row(bed = b, wake = w, dev = abs(b - bedMedian) + abs(w - wakeMedian))
    }.sortedBy { it.dev }

    val k = maxOf(3, ceil(ranked.size * 0.40).toInt())
    val best = ranked.take(k)
    val meanBed = best.map { it.bed.toDouble() }.average()
    return clampMinOfDay(meanBed.roundToInt())
  }

  private fun computeSlopeForTimes(points: List<DailyPoint>, getter: (DailyPoint) -> Int?): Double? {
    val pairs = points.mapIndexedNotNull { idx, p ->
      val t = getter(p) ?: return@mapIndexedNotNull null
      Pair(idx, t)
    }
    if (pairs.size < 2) return null

    val times = pairs.map { it.second }
    val unwrapped = unwrapMinStdDev(times).unwrapped
    val xs = pairs.map { it.first.toDouble() }
    val ys = unwrapped.map { it.toDouble() }

    return computeSlopeFromPairs(xs, ys)
  }

  private fun computeSlope(values: List<Double?>): Double? {
    val pairs = values.mapIndexedNotNull { idx, v -> v?.let { Pair(idx.toDouble(), it) } }
    if (pairs.size < 2) return null
    val xs = pairs.map { it.first }
    val ys = pairs.map { it.second }
    return computeSlopeFromPairs(xs, ys)
  }

  private fun computeSlopeFromPairs(xs: List<Double>, ys: List<Double>): Double? {
    if (xs.size != ys.size || xs.size < 2) return null
    val meanX = xs.average()
    val meanY = ys.average()
    var num = 0.0
    var den = 0.0
    for (i in xs.indices) {
      val dx = xs[i] - meanX
      num += dx * (ys[i] - meanY)
      den += dx * dx
    }
    if (den == 0.0) return null
    return num / den
  }

  private data class UnwrapStats(
    val unwrapped: List<Int>,
    val mean: Double,
    val stdDevMin: Double,
  )

  private fun computeStagePercentages(points: List<DailyPoint>): StagePercentages? {
    if (points.isEmpty()) return null
    var deep = 0
    var rem = 0
    var light = 0
    for (p in points) {
      deep += p.deepMin ?: 0
      rem += p.remMin ?: 0
      light += p.lightMin ?: 0
    }

    val total = deep + rem + light
    if (total <= 0) return null

    val pcts = roundPercentages(listOf(deep, rem, light))
    return StagePercentages(deepPct = pcts[0], remPct = pcts[1], lightPct = pcts[2])
  }

  private fun roundPercentages(parts: List<Int>): List<Int> {
    require(parts.isNotEmpty())
    val total = parts.sum()
    if (total <= 0) return parts.map { 0 }

    val raws = parts.map { it.toDouble() * 100.0 / total.toDouble() }
    val floors = raws.map { kotlin.math.floor(it).toInt() }.toMutableList()
    var remaining = 100 - floors.sum()

    // Largest remainder method: distribute leftover % points by descending fractional parts.
    val order = raws.indices
      .map { idx -> Pair(idx, raws[idx] - floors[idx].toDouble()) }
      .sortedByDescending { it.second }
      .map { it.first }

    var i = 0
    while (remaining > 0) {
      floors[order[i % order.size]] += 1
      remaining -= 1
      i += 1
    }
    return floors
  }

  private fun unwrapMinStdDev(timesMinOfDay: List<Int>): UnwrapStats {
    require(timesMinOfDay.isNotEmpty())
    var best: UnwrapStats? = null
    for (ref in timesMinOfDay) {
      val unwrapped = timesMinOfDay.map { t -> bestShift(t, ref) }
      val mean = unwrapped.map { it.toDouble() }.average()
      val std = stdDev(unwrapped.map { it.toDouble() }, mean)
      val cand = UnwrapStats(unwrapped = unwrapped, mean = mean, stdDevMin = std)
      if (best == null || cand.stdDevMin < best!!.stdDevMin) best = cand
    }
    return best!!
  }

  private fun bestShift(t: Int, ref: Int): Int {
    val a = t
    val b = t + 1440
    val c = t - 1440
    return listOf(a, b, c).minBy { v -> abs(v - ref) }
  }

  private fun stdDev(values: List<Double>, mean: Double): Double {
    if (values.isEmpty()) return 0.0
    var sum = 0.0
    for (v in values) {
      val d = v - mean
      sum += d * d
    }
    return sqrt(sum / values.size.toDouble())
  }

  private fun medianInt(values: List<Int>): Int {
    if (values.isEmpty()) return 0
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else ((sorted[mid - 1] + sorted[mid]) / 2.0).roundToInt()
  }

  private fun sliceCalendarDays(points: List<DailyPoint>, anchor: LocalDate, days: Int): List<DailyPoint> {
    val byDay = points.associateBy { it.day }
    val start = anchor.minusDays((days - 1).toLong())
    val out = ArrayList<DailyPoint>(days)
    for (i in 0 until days) {
      val day = start.plusDays(i.toLong())
      out.add(byDay[day] ?: DailyPoint(
        day = day,
        sleepMin = null,
        awakeMin = null,
        bedtimeMinOfDay = null,
        wakeMinOfDay = null,
        deepMin = null,
        remMin = null,
        lightMin = null,
        stageAvailable = false,
        steps = null,
        activeCalories = null,
      ))
    }
    return out
  }

  private fun windowAround(centerMinOfDay: Int, halfWindowMin: Int): TimeWindow {
    val start = clampMinOfDay(centerMinOfDay - halfWindowMin)
    val end = clampMinOfDay(centerMinOfDay + halfWindowMin)
    return TimeWindow(startMinOfDay = start, endMinOfDay = end)
  }

  private fun clampInt(v: Int, min: Int, max: Int): Int = v.coerceIn(min, max)

  private fun clampMinOfDay(v: Int): Int {
    val m = v % 1440
    return if (m < 0) m + 1440 else m
  }

  private fun pointCompletenessScore(p: DailyPoint): Int {
    var score = 0
    if (p.sleepMin != null && p.sleepMin > 0) score += 1000
    if (p.bedtimeMinOfDay != null) score += 200
    if (p.wakeMinOfDay != null) score += 200
    if (p.stageAvailable) score += 150
    if (p.awakeMin != null) score += 50
    if (p.steps != null) score += 30
    if (p.activeCalories != null) score += 30
    score += (p.sleepMin ?: 0).coerceAtMost(600) // tie-breaker
    return score
  }

  private fun parseDay(day: String): LocalDate? {
    val s = day.trim()
    if (s.isBlank() || s == "-") return null
    return runCatching { LocalDate.parse(s) }.getOrNull()
  }

  private fun parseZonedDateTime(isoInstant: String?, zone: ZoneId): ZonedDateTime? {
    if (isoInstant.isNullOrBlank()) return null
    val inst = runCatching { Instant.parse(isoInstant.trim()) }.getOrNull() ?: return null
    return ZonedDateTime.ofInstant(inst, zone)
  }

  private fun minutesOfDay(z: ZonedDateTime): Int = z.hour * 60 + z.minute

  // Pure formatting helpers for UI mapping (kept here to avoid Android deps).
  fun formatMinutesKo(min: Int): String {
    val h = min / 60
    val m = min % 60
    return if (h > 0) "${h}시간 ${m}분" else "${m}분"
  }

  fun formatTimeOfDay(minOfDay: Int): String {
    val m = clampMinOfDay(minOfDay)
    val h = m / 60
    val mm = m % 60
    return String.format("%02d:%02d", h, mm)
  }

  fun formatWindow(window: TimeWindow): String {
    return "${formatTimeOfDay(window.startMinOfDay)} ~ ${formatTimeOfDay(window.endMinOfDay)}"
  }
}
