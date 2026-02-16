package com.openclaw.healthuploader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
  private val dashboardAdapter = HealthDailyAdapter()
  private val dashboardClient = SupabaseDashboardClient()
  private val permissions = requiredPermissions

  private var isBusy = false

  private val prefs by lazy {
    getSharedPreferences("health_uploader", MODE_PRIVATE)
  }

  private val requestPermissions = registerForActivityResult(
    PermissionController.createRequestPermissionResultContract()
  ) { granted ->
    uiScope.launch {
      val ok = granted.containsAll(permissions)
      if (ok) {
        updateStatus("권한 허용 완료")
        onPermissionsReady(triggeredByFirstFlow = true)
      } else {
        updateStatus("권한이 일부만 허용됨: ${granted.size}/${permissions.size}")
        setUiBusy(false)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    binding.rvHealthDaily.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
    binding.rvHealthDaily.adapter = dashboardAdapter

    binding.btnGrant.setOnClickListener {
      uiScope.launch {
        setUiBusy(true)
        val ok = ensureHealthConnectAndPermissions(autoRequest = true)
        if (ok) onPermissionsReady(triggeredByFirstFlow = false)
      }
    }

    binding.btnUpload.setOnClickListener {
      uiScope.launch {
        setUiBusy(true)
        val ok = ensureHealthConnectAndPermissions(autoRequest = true)
        if (ok) {
          uploadDay(LocalDate.now(ZoneId.systemDefault()).minusDays(1))
          refreshDashboard()
          setUiBusy(false)
        }
      }
    }

    binding.btnRefreshDashboard.setOnClickListener {
      uiScope.launch {
        setUiBusy(true)
        refreshDashboard()
        setUiBusy(false)
      }
    }

    uiScope.launch {
      DailyUploadWorker.schedule(this@MainActivity)
      updateStatus("시작 중...")

      // 앱 최초 진입 시 자동 권한 흐름
      setUiBusy(true)
      val ok = ensureHealthConnectAndPermissions(autoRequest = true)
      if (ok) {
        onPermissionsReady(triggeredByFirstFlow = true)
      }
    }
  }

  private suspend fun onPermissionsReady(triggeredByFirstFlow: Boolean) {
    DailyUploadWorker.schedule(this@MainActivity)

    if (triggeredByFirstFlow && !isInitialBackfillDone()) {
      runInitialBackfillLast90Days()
    } else {
      updateStatus("권한 OK. 대시보드 갱신 중...")
    }

    refreshDashboard()
    setUiBusy(false)
  }

  private suspend fun ensureHealthConnectAndPermissions(autoRequest: Boolean): Boolean {
    val status = HealthConnectClient.getSdkStatus(this)
    if (status != HealthConnectClient.SDK_AVAILABLE) {
      updateStatus("Health Connect 필요. 설치 화면으로 이동")
      openHealthConnectStorePage()
      return false
    }

    val client = HealthConnectClient.getOrCreate(this)
    val granted = client.permissionController.getGrantedPermissions()
    val missing = permissions.subtract(granted)

    return if (missing.isEmpty()) {
      true
    } else {
      if (autoRequest) {
        updateStatus("권한 요청 중...")
        requestPermissions.launch(permissions)
      }
      false
    }
  }

  private fun openHealthConnectStorePage() {
    try {
      startActivity(
        Intent(
          Intent.ACTION_VIEW,
          Uri.parse("market://details?id=com.google.android.apps.healthdata")
        )
      )
    } catch (_: ActivityNotFoundException) {
      startActivity(
        Intent(
          Intent.ACTION_VIEW,
          Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
        )
      )
    }
  }

  private suspend fun runInitialBackfillLast90Days() {
    val zone = ZoneId.systemDefault()
    val total = 90
    var success = 0
    var fail = 0

    for (i in 1..total) {
      val day = LocalDate.now(zone).minusDays(i.toLong())
      updateStatus("초기 백필 업로드 중... ($i/$total) $day")

      val ok = withContext(Dispatchers.IO) {
        runCatching {
          val payload = collectDaily(day)
          postToSupabase(payload)
        }.getOrDefault(false)
      }

      if (ok) success++ else fail++
    }

    markInitialBackfillDone()
    updateStatus("초기 백필 완료: 성공 $success / 실패 $fail")
  }

  private fun isInitialBackfillDone(): Boolean {
    return prefs.getBoolean(KEY_INITIAL_BACKFILL_V7_DONE, false)
  }

  private fun markInitialBackfillDone() {
    prefs.edit().putBoolean(KEY_INITIAL_BACKFILL_V7_DONE, true).apply()
  }

  private suspend fun uploadDay(day: LocalDate) {
    updateStatus("집계 중: $day")
    val payload = withContext(Dispatchers.IO) { collectDaily(day) }

    updateStatus("업로드 중: $day")
    val ok = withContext(Dispatchers.IO) { postToSupabase(payload) }
    updateStatus(if (ok) "업로드 성공: $day" else "업로드 실패: $day")
  }

  private suspend fun refreshDashboard() {
    showDashboardState("대시보드 불러오는 중...")

    val result = withContext(Dispatchers.IO) {
      runCatching { dashboardClient.fetchLatest30() }
    }

    result.onSuccess { rows ->
      if (rows.isEmpty()) {
        dashboardAdapter.submit(emptyList())
        showDashboardState("데이터 없음 (최근 30일 집계 없음)")
      } else {
        dashboardAdapter.submit(rows)
        hideDashboardState()
      }
    }.onFailure { e ->
      dashboardAdapter.submit(emptyList())
      showDashboardState("대시보드 오류: ${e.message ?: "알 수 없는 오류"}")
    }
  }

  private fun setUiBusy(busy: Boolean) {
    isBusy = busy
    binding.btnGrant.isEnabled = !busy
    binding.btnUpload.isEnabled = !busy
    binding.btnRefreshDashboard.isEnabled = !busy
    binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
  }

  private suspend fun updateStatus(msg: String) {
    binding.tvStatus.text = msg
  }

  private fun showDashboardState(message: String) {
    binding.tvDashboardState.text = message
    binding.tvDashboardState.visibility = View.VISIBLE
  }

  private fun hideDashboardState() {
    binding.tvDashboardState.visibility = View.GONE
  }

  private fun dayWindow(day: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
    val start = day.atStartOfDay(zone).toInstant()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant()
    return start to end
  }

  private suspend fun collectDaily(day: LocalDate): JSONObject {
    val zone = ZoneId.systemDefault()
    val client = HealthConnectClient.getOrCreate(this)

    val (start, end) = dayWindow(day, zone)

    val sleepStart = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
    val sleepEnd = day.plusDays(1).atTime(12, 0).atZone(zone).toInstant()

    val sleepSessions = client.readRecords(
      androidx.health.connect.client.request.ReadRecordsRequest(
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
        put("note", "v0.7 auto-permission + backfill + dashboard")
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
    private const val KEY_INITIAL_BACKFILL_V7_DONE = "initial_backfill_v7_done"

    val requiredPermissions = setOf(
      HealthPermission.getReadPermission(SleepSessionRecord::class),
      HealthPermission.getReadPermission(StepsRecord::class),
      HealthPermission.getReadPermission(ExerciseSessionRecord::class),
      HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
      HealthPermission.getReadPermission(DistanceRecord::class),
    )
  }
}
