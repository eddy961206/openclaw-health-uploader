package com.openclaw.healthuploader

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class DailyUploadWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val status = HealthConnectClient.getSdkStatus(applicationContext)
    if (status != HealthConnectClient.SDK_AVAILABLE) {
      // Health Connect unavailable: try again tomorrow.
      return Result.success()
    }

    val client = HealthConnectClient.getOrCreate(applicationContext)
    val required = MainActivity.requiredPermissions
    val granted = client.permissionController.getGrantedPermissions()
    if (!granted.containsAll(required)) {
      // Permission is user-managed in foreground activity.
      return Result.success()
    }

    val zone = ZoneId.systemDefault()
    val day = LocalDate.now(zone).minusDays(1)
    val payload = collectDaily(client, day, zone)
    val ok = postToSupabase(payload)

    return if (ok) Result.success() else Result.retry()
  }

  private fun dayWindow(day: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
    val start = day.atStartOfDay(zone).toInstant()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant()
    return start to end
  }

  private suspend fun collectDaily(
    client: HealthConnectClient,
    day: LocalDate,
    zone: ZoneId,
  ): JSONObject {
    val (start, end) = dayWindow(day, zone)

    val sleepStart = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
    val sleepEnd = day.plusDays(1).atTime(12, 0).atZone(zone).toInstant()

    val sleepSessions = client.readRecords(
      ReadRecordsRequest(
        SleepSessionRecord::class,
        timeRangeFilter = TimeRangeFilter.between(sleepStart, sleepEnd),
      )
    ).records

    val best = sleepSessions
      .map { r ->
        val ovStart = maxOf(r.startTime, start)
        val ovEnd = minOf(r.endTime, end)
        val overlap = (ovEnd.toEpochMilli() - ovStart.toEpochMilli()).coerceAtLeast(0)
        Pair(r, overlap)
      }
      .maxByOrNull { it.second }
      ?.first

    val sleepDurationMin = best?.let {
      ((it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60000).toInt()
    }

    val stepsAgg = client.aggregate(
      AggregateRequest(
        metrics = setOf(StepsRecord.COUNT_TOTAL),
        timeRangeFilter = TimeRangeFilter.between(start, end),
      )
    )

    val calAgg = client.aggregate(
      AggregateRequest(
        metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
        timeRangeFilter = TimeRangeFilter.between(start, end),
      )
    )

    val distAgg = client.aggregate(
      AggregateRequest(
        metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
        timeRangeFilter = TimeRangeFilter.between(start, end),
      )
    )

    val workouts = client.readRecords(
      ReadRecordsRequest(
        ExerciseSessionRecord::class,
        timeRangeFilter = TimeRangeFilter.between(start, end),
      )
    ).records

    val steps = stepsAgg[StepsRecord.COUNT_TOTAL]?.toLong()
    val activeCalories = calAgg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
    val distanceKm = distAgg[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.div(1000.0)

    return JSONObject().apply {
      put("day", day.toString())
      put("sleep_start", best?.startTime?.toString())
      put("sleep_end", best?.endTime?.toString())
      put("sleep_duration_minutes", sleepDurationMin)
      put("steps", steps)
      put("active_calories", activeCalories)
      put("workouts_count", workouts.size)
      put("distance_km", distanceKm)
      put("source", JSONObject().apply {
        put("tz", zone.id)
        put("collected_at", ZonedDateTime.now(zone).toInstant().toString())
        put("note", "v0.2 auto worker daily aggregates")
      })
    }
  }

  private fun postToSupabase(payload: JSONObject): Boolean {
    val endpoint = BuildConfig.INGEST_ENDPOINT
    val secret = BuildConfig.INGEST_SECRET
    if (endpoint.isBlank() || secret.isBlank()) return false

    val client = OkHttpClient()
    val body = payload.toString().toRequestBody("application/json".toMediaType())

    val req = Request.Builder()
      .url(endpoint)
      .addHeader("content-type", "application/json")
      .addHeader("x-ingest-secret", secret)
      .post(body)
      .build()

    client.newCall(req).execute().use { resp ->
      return resp.isSuccessful
    }
  }

  companion object {
    private const val UNIQUE_WORK_NAME = "daily_health_upload"

    fun schedule(context: Context) {
      val request = PeriodicWorkRequestBuilder<DailyUploadWorker>(24, TimeUnit.HOURS)
        .setInitialDelay(initialDelayToTargetHour(9, 5), TimeUnit.MILLISECONDS)
        .build()

      WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        UNIQUE_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        request,
      )
    }

    private fun initialDelayToTargetHour(hour: Int, minute: Int): Long {
      val now = ZonedDateTime.now()
      var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
      if (!next.isAfter(now)) next = next.plusDays(1)
      return Duration.between(now, next).toMillis().coerceAtLeast(0)
    }
  }
}
