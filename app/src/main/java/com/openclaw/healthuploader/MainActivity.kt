package com.openclaw.healthuploader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.openclaw.healthuploader.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class MainActivity : AppCompatActivity() {

  private lateinit var binding: ActivityMainBinding
  private val uiScope = CoroutineScope(Dispatchers.Main)

  private val permissions = requiredPermissions

  private val requestPermissions = registerForActivityResult(
    PermissionController.createRequestPermissionResultContract()
  ) { granted ->
    uiScope.launch {
      val ok = granted.containsAll(permissions)
      if (ok) {
        DailyUploadWorker.schedule(this@MainActivity)
        updateStatus("권한 완료. 자동 업로드 예약됨(매일 09:05 근처)")
      } else {
        updateStatus("권한 granted: ${granted.size}/${permissions.size}")
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    binding.btnGrant.setOnClickListener {
      uiScope.launch { ensureHealthConnectAndPermissions() }
    }

    binding.btnUpload.setOnClickListener {
      uiScope.launch {
        val ok = ensureHealthConnectAndPermissions()
        if (!ok) return@launch
        uploadYesterday()
      }
    }

    uiScope.launch {
      DailyUploadWorker.schedule(this@MainActivity)
      updateStatus("앱 준비됨 (자동 업로드: 매일 09:05 근처)")
    }
  }

  private suspend fun updateStatus(msg: String) {
    binding.tvStatus.text = msg
  }

  private suspend fun ensureHealthConnectAndPermissions(): Boolean {
    val status = HealthConnectClient.getSdkStatus(this)
    if (status != HealthConnectClient.SDK_AVAILABLE) {
      updateStatus("Health Connect가 필요함. 설치 화면으로 이동 중...")
      try {
        startActivity(
          Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=com.google.android.apps.healthdata")
          )
        )
      } catch (e: ActivityNotFoundException) {
        startActivity(
          Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
          )
        )
      }
      return false
    }

    val client = HealthConnectClient.getOrCreate(this)
    val granted = client.permissionController.getGrantedPermissions()
    val missing = permissions.subtract(granted)
    return if (missing.isEmpty()) {
      updateStatus("권한 OK")
      true
    } else {
      updateStatus("권한 요청 중...")
      requestPermissions.launch(permissions)
      false
    }
  }

  private suspend fun uploadYesterday() {
    val zone = ZoneId.systemDefault()
    val day = LocalDate.now(zone).minusDays(1)

    updateStatus("집계 중: $day")
    val payload = withContext(Dispatchers.IO) { collectDaily(day) }

    updateStatus("업로드 중...")
    val ok = withContext(Dispatchers.IO) { postToSupabase(payload) }
    updateStatus(if (ok) "업로드 성공: $day" else "업로드 실패 (네트워크/키 확인)")
  }

  private fun dayWindow(day: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
    // Use local day boundaries
    val start = day.atStartOfDay(zone).toInstant()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant()
    return start to end
  }

  private suspend fun collectDaily(day: LocalDate): JSONObject {
    val zone = ZoneId.systemDefault()
    val client = HealthConnectClient.getOrCreate(this)

    val (start, end) = dayWindow(day, zone)

    // Sleep: query a wider window to catch overnight sessions
    val sleepStart = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
    val sleepEnd = day.plusDays(1).atTime(12, 0).atZone(zone).toInstant()

    val sleepSessions = client.readRecords(
      androidx.health.connect.client.request.ReadRecordsRequest(
        SleepSessionRecord::class,
        timeRangeFilter = TimeRangeFilter.between(sleepStart, sleepEnd),
      )
    ).records

    // Pick the longest session that overlaps the day
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

    // Aggregates
    val stepsAgg = client.aggregate(
      AggregateRequest(
        metrics = setOf(StepsRecord.COUNT_TOTAL),
        timeRangeFilter = TimeRangeFilter.between(start, end)
      )
    )

    val calAgg = client.aggregate(
      AggregateRequest(
        metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
        timeRangeFilter = TimeRangeFilter.between(start, end)
      )
    )

    val distAgg = client.aggregate(
      AggregateRequest(
        metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
        timeRangeFilter = TimeRangeFilter.between(start, end)
      )
    )

    val workouts = client.readRecords(
      androidx.health.connect.client.request.ReadRecordsRequest(
        ExerciseSessionRecord::class,
        timeRangeFilter = TimeRangeFilter.between(start, end),
      )
    ).records

    val steps = stepsAgg[StepsRecord.COUNT_TOTAL]?.toLong()
    val activeCalories = calAgg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
    val distanceKm = distAgg[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.div(1000.0)

    return JSONObject().apply {
      put("day", day.toString())

      // sleep
      put("sleep_start", best?.startTime?.toString())
      put("sleep_end", best?.endTime?.toString())
      put("sleep_duration_minutes", sleepDurationMin)

      // activity
      put("steps", steps)
      put("active_calories", activeCalories)
      put("workouts_count", workouts.size)
      put("distance_km", distanceKm)

      // metadata
      put("source", JSONObject().apply {
        put("tz", zone.id)
        put("collected_at", ZonedDateTime.now(zone).toInstant().toString())
        put("note", "v0.1 health connect daily aggregates")
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
    val requiredPermissions = setOf(
      HealthPermission.getReadPermission(SleepSessionRecord::class),
      HealthPermission.getReadPermission(StepsRecord::class),
      HealthPermission.getReadPermission(ExerciseSessionRecord::class),
      HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
      HealthPermission.getReadPermission(DistanceRecord::class),
    )
  }
}
