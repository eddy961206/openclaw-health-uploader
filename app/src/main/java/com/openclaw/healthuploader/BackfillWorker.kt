package com.openclaw.healthuploader

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class BackfillWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val offset = inputData.getInt(KEY_DAY_OFFSET, 1)
    val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val status = HealthConnectClient.getSdkStatus(applicationContext)
    if (status != HealthConnectClient.SDK_AVAILABLE) return Result.success()

    val client = HealthConnectClient.getOrCreate(applicationContext)
    val granted = client.permissionController.getGrantedPermissions()
    if (!granted.containsAll(MainActivity.requiredPermissions)) return Result.success()

    prefs.edit().putBoolean(KEY_RUNNING, true).apply()

    val zone = ZoneId.systemDefault()
    val day = LocalDate.now(zone).minusDays(offset.toLong())

    val ok = runCatching {
      val payload = collectDaily(client, day, zone)
      postToSupabase(payload)
    }.getOrDefault(false)

    val success = prefs.getInt(KEY_SUCCESS, 0) + if (ok) 1 else 0
    val fail = prefs.getInt(KEY_FAIL, 0) + if (ok) 0 else 1

    prefs.edit()
      .putInt(KEY_PROGRESS, offset)
      .putInt(KEY_SUCCESS, success)
      .putInt(KEY_FAIL, fail)
      .apply()

    if (offset < MAX_DAYS) {
      enqueueNext(applicationContext, offset + 1)
    } else {
      prefs.edit()
        .putBoolean(KEY_RUNNING, false)
        .putBoolean(KEY_DONE, true)
        .apply()
    }

    return Result.success()
  }

  private fun enqueueNext(context: Context, offset: Int) {
    val req = OneTimeWorkRequestBuilder<BackfillWorker>()
      .setInputData(Data.Builder().putInt(KEY_DAY_OFFSET, offset).build())
      .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
      UNIQUE_NAME,
      ExistingWorkPolicy.APPEND,
      req,
    )
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

    val granted = client.permissionController.getGrantedPermissions()
    val sleep = SleepDailyCollector.collectForDay(
      client = client,
      day = day,
      zone = zone,
      grantedPermissions = granted,
      enableSleepVitals = true,
    )
    val sleepDurationMin = sleep.sleepWindowMinutes
    val sleepStartIso = sleep.sleepStart?.toString()
    val sleepEndIso = sleep.sleepEnd?.toString()

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
      put("sleep_start", sleepStartIso)
      put("sleep_end", sleepEndIso)
      put("sleep_duration_minutes", sleepDurationMin)
      if (BuildConfig.SEND_SLEEP_V2_FIELDS) {
        putIfNotNull("sleep_minutes", sleep.sleepMinutes)
        putIfNotNull("sleep_awake_minutes", sleep.awakeMinutes)
        putIfNotNull("sleep_light_minutes", sleep.lightMinutes)
        putIfNotNull("sleep_deep_minutes", sleep.deepMinutes)
        putIfNotNull("sleep_rem_minutes", sleep.remMinutes)
        putIfNotNull("sleep_score", sleep.sleepScore)
        putIfNotNull("sleep_avg_hr", sleep.avgHr)
        putIfNotNull("sleep_spo2", sleep.spo2)
      }
      put("steps", steps)
      put("active_calories", activeCalories)
      put("workouts_count", workouts.size)
      put("distance_km", distanceKm)
      put("source", JSONObject().apply {
        put("tz", zone.id)
        put("collected_at", ZonedDateTime.now(zone).toInstant().toString())
        put("note", "v0.9 sleep-first backfill worker")
        put(
          "sleep_v2",
          JSONObject().apply {
            putIfNotNull("sleep_minutes", sleep.sleepMinutes)
            putIfNotNull("sleep_awake_minutes", sleep.awakeMinutes)
            putIfNotNull("sleep_light_minutes", sleep.lightMinutes)
            putIfNotNull("sleep_deep_minutes", sleep.deepMinutes)
            putIfNotNull("sleep_rem_minutes", sleep.remMinutes)
            putIfNotNull("sleep_score", sleep.sleepScore)
            putIfNotNull("sleep_avg_hr", sleep.avgHr)
            putIfNotNull("sleep_spo2", sleep.spo2)
            putIfNotNull("stage_records", sleep.debug.stageCount)
            if (sleep.debug.stageTypes.isNotEmpty()) put("stage_types", sleep.debug.stageTypes.joinToString(","))
          }
        )
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
    const val PREFS_NAME = "health_uploader_prefs"
    const val KEY_RUNNING = "backfill_running"
    const val KEY_DONE = "backfill_done"
    const val KEY_PROGRESS = "backfill_progress"
    const val KEY_SUCCESS = "backfill_success"
    const val KEY_FAIL = "backfill_fail"
    private const val KEY_DAY_OFFSET = "day_offset"
    private const val MAX_DAYS = 90
    private const val UNIQUE_NAME = "initial_backfill_chain_v2"

    fun startIfNeeded(context: Context) {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      if (prefs.getBoolean(KEY_DONE, false) || prefs.getBoolean(KEY_RUNNING, false)) return

      prefs.edit()
        .putBoolean(KEY_RUNNING, true)
        .putInt(KEY_PROGRESS, 0)
        .putInt(KEY_SUCCESS, 0)
        .putInt(KEY_FAIL, 0)
        .apply()

      val req = OneTimeWorkRequestBuilder<BackfillWorker>()
        .setInputData(Data.Builder().putInt(KEY_DAY_OFFSET, 1).build())
        .build()

      WorkManager.getInstance(context).enqueueUniqueWork(
        UNIQUE_NAME,
        ExistingWorkPolicy.REPLACE,
        req,
      )
    }
  }
}
