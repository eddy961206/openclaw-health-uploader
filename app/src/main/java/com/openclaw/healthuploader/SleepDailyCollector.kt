package com.openclaw.healthuploader

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

object SleepDailyCollector {
  private const val TAG = "SleepDailyCollector"

  data class SleepDaily(
    val sleepStart: Instant?,
    val sleepEnd: Instant?,
    // The session window (time in bed) in minutes.
    val sleepWindowMinutes: Int?,
    // Actual sleep minutes derived from stages (light+deep+rem) when available, else fallback to window.
    val sleepMinutes: Int?,
    val awakeMinutes: Int?,
    val lightMinutes: Int?,
    val deepMinutes: Int?,
    val remMinutes: Int?,
    val sleepScore: Double?, // Not currently exposed reliably via Health Connect for all sources.
    val avgHr: Double?,
    val spo2: Double?, // Optional; not collected yet in this app (kept nullable for compatibility).
    val debug: Debug,
  )

  data class Debug(
    val sessionCount: Int,
    val stageCount: Int?,
    val stageTypes: Set<String>,
    val usedStageFallback: Boolean,
    val note: String,
  )

  suspend fun collectForDay(
    client: HealthConnectClient,
    day: LocalDate,
    zone: ZoneId,
    grantedPermissions: Set<String>,
    enableSleepVitals: Boolean,
  ): SleepDaily {
    val (dayStart, dayEnd) = dayWindow(day, zone)

    // Wider window to catch overnight sessions.
    val queryStart = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
    val queryEnd = day.plusDays(1).atTime(12, 0).atZone(zone).toInstant()

    val sessions = runCatching {
      client.readRecords(
        ReadRecordsRequest(
          SleepSessionRecord::class,
          timeRangeFilter = TimeRangeFilter.between(queryStart, queryEnd),
        )
      ).records
    }.onFailure {
      Log.w(TAG, "sleep sessions read failed day=$day err=${it.message}")
    }.getOrDefault(emptyList())

    val bestSession = sessions
      .map { r ->
        val ovStart = maxOf(r.startTime, dayStart)
        val ovEnd = minOf(r.endTime, dayEnd)
        val overlapMs = (ovEnd.toEpochMilli() - ovStart.toEpochMilli()).coerceAtLeast(0)
        Pair(r, overlapMs)
      }
      .maxByOrNull { it.second }
      ?.first

    val sessionStart = bestSession?.startTime
    val sessionEnd = bestSession?.endTime
    val windowMin = bestSession?.let { minutesBetween(it.startTime, it.endTime) }

    // connect-client:1.1.0-alpha07 doesn't have SleepStageRecord. Stages are embedded in SleepSessionRecord.
    val stages: List<SleepSessionRecord.Stage> = bestSession?.stages.orEmpty()

    val finalStart = sessionStart
    val finalEnd = sessionEnd

    val stageTypesSeen = mutableSetOf<String>()
    var awakeMin: Int? = null
    var lightMin: Int? = null
    var deepMin: Int? = null
    var remMin: Int? = null

    if (stages.isNotEmpty() && finalStart != null && finalEnd != null) {
      var awake = 0L
      var light = 0L
      var deep = 0L
      var rem = 0L
      var sleeping = 0L
      var other = 0L

      var sawAwake = false
      var sawLight = false
      var sawDeep = false
      var sawRem = false
      var sawSleeping = false

      for (s in stages) {
        val type = stageTypeName(s.stage)
        stageTypesSeen.add(type)

        val ov = overlapMinutes(finalStart, finalEnd, s.startTime, s.endTime)
        when (s.stage) {
          SleepSessionRecord.STAGE_TYPE_AWAKE,
          SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
          SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> {
            awake += ov
            sawAwake = true
          }

          SleepSessionRecord.STAGE_TYPE_LIGHT -> {
            light += ov
            sawLight = true
          }

          SleepSessionRecord.STAGE_TYPE_DEEP -> {
            deep += ov
            sawDeep = true
          }

          SleepSessionRecord.STAGE_TYPE_REM -> {
            rem += ov
            sawRem = true
          }

          SleepSessionRecord.STAGE_TYPE_SLEEPING -> {
            sleeping += ov
            sawSleeping = true
          }

          else -> other += ov
        }
      }

      awakeMin = if (sawAwake) awake.toInt() else null
      lightMin = if (sawLight) light.toInt() else null
      deepMin = if (sawDeep) deep.toInt() else null
      remMin = if (sawRem) rem.toInt() else null

      if (other > 0) {
        Log.d(TAG, "sleep stages day=$day other/unknown minutes=$other types=$stageTypesSeen")
      }
      if (sawSleeping && !sawLight && !sawDeep && !sawRem) {
        Log.d(TAG, "sleep stages day=$day only SLEEPING bucket present (no light/deep/rem breakdown)")
      }
    } else if (bestSession != null) {
      Log.d(TAG, "sleep stages empty day=$day session=true")
    }

    val sleepMin: Int? = when {
      stages.isNotEmpty() && finalStart != null && finalEnd != null -> {
        // Stage-based sleep minutes (exclude awake/out-of-bed). Include generic SLEEPING bucket if present.
        var total = 0
        for (s in stages) {
          val ov = overlapMinutes(finalStart, finalEnd, s.startTime, s.endTime).toInt()
          when (s.stage) {
            SleepSessionRecord.STAGE_TYPE_LIGHT,
            SleepSessionRecord.STAGE_TYPE_DEEP,
            SleepSessionRecord.STAGE_TYPE_REM,
            SleepSessionRecord.STAGE_TYPE_SLEEPING -> total += ov
          }
        }
        total
      }
      windowMin != null -> windowMin
      else -> null
    }

    val avgHr: Double? = if (enableSleepVitals && grantedPermissions.contains(MainActivity.permHeartRate)) {
      computeAvgHrDuring(client, finalStart, finalEnd)
    } else {
      null
    }

    val note = buildString {
      append("sessions=${sessions.size}")
      append(", bestSession=${bestSession != null}")
      append(", stagesFromSession=${bestSession != null}")
      append(", stages=${stages.size}")
      if (enableSleepVitals) append(", vitals=true") else append(", vitals=false")
    }

    Log.d(TAG, "sleep daily day=$day start=$finalStart end=$finalEnd sleepMin=$sleepMin windowMin=$windowMin $note")

    return SleepDaily(
      sleepStart = finalStart,
      sleepEnd = finalEnd,
      sleepWindowMinutes = if (finalStart != null && finalEnd != null) minutesBetween(finalStart, finalEnd) else windowMin,
      sleepMinutes = sleepMin,
      awakeMinutes = awakeMin,
      lightMinutes = lightMin,
      deepMinutes = deepMin,
      remMinutes = remMin,
      sleepScore = null,
      avgHr = avgHr,
      spo2 = null,
      debug = Debug(
        sessionCount = sessions.size,
        stageCount = if (bestSession != null) stages.size else null,
        stageTypes = stageTypesSeen.toSet(),
        usedStageFallback = false,
        note = note,
      ),
    )
  }

  suspend fun collectBedtimeConsistencyHint(
    client: HealthConnectClient,
    zone: ZoneId,
    days: Int = 7,
  ): String? {
    val today = LocalDate.now(zone)
    val times = mutableListOf<Int>()

    for (i in 1..days) {
      val day = today.minusDays(i.toLong())
      val (dayStart, dayEnd) = dayWindow(day, zone)
      val queryStart = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
      val queryEnd = day.plusDays(1).atTime(12, 0).atZone(zone).toInstant()

      val sessions = runCatching {
        client.readRecords(
          ReadRecordsRequest(
            SleepSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(queryStart, queryEnd),
          )
        ).records
      }.getOrDefault(emptyList())

      val best = sessions
        .map { r ->
          val ovStart = maxOf(r.startTime, dayStart)
          val ovEnd = minOf(r.endTime, dayEnd)
          val overlapMs = (ovEnd.toEpochMilli() - ovStart.toEpochMilli()).coerceAtLeast(0)
          Pair(r, overlapMs)
        }
        .maxByOrNull { it.second }
        ?.first
        ?: continue

      val z = ZonedDateTime.ofInstant(best.startTime, zone)
      val minutes = z.hour * 60 + z.minute
      // Normalize: bedtimes usually between 18:00 and 11:59. Shift morning times to next-day range.
      val normalized = if (minutes < 12 * 60) minutes + 24 * 60 else minutes
      times.add(normalized)
    }

    if (times.size < 4) return null

    val min = times.minOrNull() ?: return null
    val max = times.maxOrNull() ?: return null
    val range = max - min

    return when {
      range <= 60 -> "취침 시간이 꽤 일정해 (최근 ${times.size}일 범위 ${range}분)"
      range <= 120 -> "취침 시간이 조금 들쑥날쑥해 (최근 ${times.size}일 범위 ${range}분)"
      else -> "취침 시간이 많이 흔들려 (최근 ${times.size}일 범위 ${range}분)"
    }
  }

  private fun dayWindow(day: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
    val start = day.atStartOfDay(zone).toInstant()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant()
    return start to end
  }

  private fun minutesBetween(start: Instant, end: Instant): Int {
    return ((end.toEpochMilli() - start.toEpochMilli()) / 60000.0).roundToInt().coerceAtLeast(0)
  }

  private fun overlapMinutes(
    aStart: Instant,
    aEnd: Instant,
    bStart: Instant,
    bEnd: Instant,
  ): Long {
    val ovStart = maxOf(aStart, bStart)
    val ovEnd = minOf(aEnd, bEnd)
    val ms = (ovEnd.toEpochMilli() - ovStart.toEpochMilli()).coerceAtLeast(0)
    return (ms / 60000.0).roundToInt().toLong()
  }

  private fun stageTypeName(stage: Int): String {
    return when (stage) {
      SleepSessionRecord.STAGE_TYPE_AWAKE -> "AWAKE"
      SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "AWAKE_IN_BED"
      SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "OUT_OF_BED"
      SleepSessionRecord.STAGE_TYPE_LIGHT -> "LIGHT"
      SleepSessionRecord.STAGE_TYPE_DEEP -> "DEEP"
      SleepSessionRecord.STAGE_TYPE_REM -> "REM"
      SleepSessionRecord.STAGE_TYPE_SLEEPING -> "SLEEPING"
      else -> "UNKNOWN"
    }
  }

  private suspend fun computeAvgHrDuring(
    client: HealthConnectClient,
    start: Instant?,
    end: Instant?,
  ): Double? {
    if (start == null || end == null) return null

    val records = runCatching {
      client.readRecords(
        ReadRecordsRequest(
          HeartRateRecord::class,
          timeRangeFilter = TimeRangeFilter.between(start, end),
        )
      ).records
    }.onFailure {
      Log.d(TAG, "heart rate read failed err=${it.message}")
    }.getOrDefault(emptyList())

    if (records.isEmpty()) return null

    var sum = 0.0
    var count = 0
    for (r in records) {
      for (s in r.samples) {
        sum += s.beatsPerMinute
        count += 1
      }
    }
    if (count == 0) return null
    return sum / count.toDouble()
  }
}
