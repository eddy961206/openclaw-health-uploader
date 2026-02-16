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
  private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

  private val permissions = requiredPermissions

  private var busyCount = 0
  private var permissionRequestInFlight = false
  private var pendingHealthConnectInstallCheck = false

  private val requestPermissions = registerForActivityResult(
    PermissionController.createRequestPermissionResultContract()
  ) { granted ->
    uiScope.launch {
      permissionRequestInFlight = false
      updateActionButtonsState()

      val ok = granted.containsAll(permissions)
      if (ok) {
        onPermissionsReady()
      } else {
        updateStatus("권한이 아직 부족해 (${granted.size}/${permissions.size})")
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
        withBusyTask {
          val granted = ensureHealthConnectAndPermissions(autoFlow = false, openInstallIfMissing = true)
          if (granted) onPermissionsReady()
        }
      }
    }

    binding.btnUpload.setOnClickListener {
      uiScope.launch {
        withBusyTask {
          val granted = ensureHealthConnectAndPermissions(autoFlow = false, openInstallIfMissing = true)
          if (!granted) return@withBusyTask

          val day = LocalDate.now(ZoneId.systemDefault()).minusDays(1)
          uploadSingleDay(day, "수동 업로드")
        }
      }
    }

    binding.btnRefreshDashboard.setOnClickListener {
      uiScope.launch {
        withBusyTask { refreshDashboard() }
      }
    }

    val skipAutoFlowForTest = intent?.getBooleanExtra(EXTRA_SKIP_AUTO_FLOW, false) == true
    if (skipAutoFlowForTest) {
      updateStatus("테스트 모드")
    } else {
      uiScope.launch { runInitialFlow() }
    }
  }

  override fun onResume() {
    super.onResume()
    renderBackfillStatus()

    if (!pendingHealthConnectInstallCheck) return
    if (busyCount > 0 || permissionRequestInFlight) return

    pendingHealthConnectInstallCheck = false
    uiScope.launch {
      withBusyTask {
        val granted = ensureHealthConnectAndPermissions(autoFlow = true, openInstallIfMissing = false)
        if (granted) onPermissionsReady()
      }
    }
  }

  private suspend fun runInitialFlow() {
    DailyUploadWorker.schedule(this)

    withBusyTask { refreshDashboard() }

    val started = prefs.getBoolean(PREF_AUTO_PERMISSION_FLOW_STARTED, false)
    if (started) {
      updateStatus("준비 완료. 자동 업로드는 매일 09:05 근처에 실행돼")
      return
    }

    prefs.edit().putBoolean(PREF_AUTO_PERMISSION_FLOW_STARTED, true).apply()

    withBusyTask {
      updateStatus("최초 실행: Health Connect 권한 확인 중...")
      val granted = ensureHealthConnectAndPermissions(autoFlow = true, openInstallIfMissing = true)
      if (granted) onPermissionsReady()
    }
  }

  private fun updateStatus(msg: String) {
    binding.tvStatus.text = msg
  }

  private suspend fun ensureHealthConnectAndPermissions(
    autoFlow: Boolean,
    openInstallIfMissing: Boolean,
  ): Boolean {
    val status = HealthConnectClient.getSdkStatus(this)
    if (status != HealthConnectClient.SDK_AVAILABLE) {
      if (openInstallIfMissing) {
        updateStatus("Health Connect가 필요해. 설치 화면으로 이동할게")
        openHealthConnectInstallPage()
        if (autoFlow) pendingHealthConnectInstallCheck = true
      } else {
        updateStatus("Health Connect 설치 후 다시 돌아와줘")
      }
      return false
    }

    val client = HealthConnectClient.getOrCreate(this)
    val granted = client.permissionController.getGrantedPermissions()
    val missing = permissions.subtract(granted)
    if (missing.isEmpty()) {
      updateStatus("권한 확인 완료")
      return true
    }

    updateStatus("Health Connect 권한 요청 중...")
    permissionRequestInFlight = true
    updateActionButtonsState()
    requestPermissions.launch(permissions)
    return false
  }

  private fun openHealthConnectInstallPage() {
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
  }

  private suspend fun onPermissionsReady() {
    DailyUploadWorker.schedule(this@MainActivity)
    BackfillWorker.startIfNeeded(this@MainActivity)
    renderBackfillStatus()
    refreshDashboard()
  }

  private fun renderBackfillStatus() {
    val running = prefs.getBoolean(BackfillWorker.KEY_RUNNING, false)
    val done = prefs.getBoolean(BackfillWorker.KEY_DONE, false)
    val progress = prefs.getInt(BackfillWorker.KEY_PROGRESS, 0)
    val success = prefs.getInt(BackfillWorker.KEY_SUCCESS, 0)
    val fail = prefs.getInt(BackfillWorker.KEY_FAIL, 0)

    val message = when {
      running -> "백필 진행 중: $progress/90 (성공 $success, 실패 $fail)"
      done -> "백필 완료: 성공 $success, 실패 $fail"
      else -> "권한 완료. 자동 업로드 예약됨(매일 09:05 근처)"
    }
    updateStatus(message)
  }

  private suspend fun uploadSingleDay(day: LocalDate, reason: String) {
    updateStatus("$reason: 집계 중 ($day)")

    val payload = withContext(Dispatchers.IO) {
      runCatching { collectDaily(day) }
    }

    if (payload.isFailure) {
      updateStatus("$reason 실패: 집계 오류")
      return
    }

    updateStatus("$reason: 업로드 중 ($day)")
    val uploaded = withContext(Dispatchers.IO) {
      runCatching { postToSupabase(payload.getOrThrow()) }.getOrDefault(false)
    }

    updateStatus(if (uploaded) "$reason 성공: $day" else "$reason 실패: $day")
  }

  private suspend fun refreshDashboard() {
    showDashboardState("대시보드 불러오는 중...")

    val result = withContext(Dispatchers.IO) {
      runCatching { dashboardClient.fetchLatest30() }
    }

    result.onSuccess { rows ->
      if (rows.isEmpty()) {
        dashboardAdapter.submit(emptyList())
        showDashboardState("데이터 없음 (health_daily 0건)")
      } else {
        dashboardAdapter.submit(rows)
        hideDashboardState()
      }
    }.onFailure { e ->
      dashboardAdapter.submit(emptyList())
      showDashboardState("대시보드 오류: ${e.message ?: "알 수 없는 오류"}")
    }
  }

  private fun showDashboardState(message: String) {
    binding.tvDashboardState.text = message
    binding.tvDashboardState.visibility = android.view.View.VISIBLE
  }

  private fun hideDashboardState() {
    binding.tvDashboardState.visibility = android.view.View.GONE
  }

  private fun beginBusy() {
    busyCount += 1
    updateActionButtonsState()
  }

  private fun endBusy() {
    busyCount = (busyCount - 1).coerceAtLeast(0)
    updateActionButtonsState()
  }

  private fun updateActionButtonsState() {
    val enabled = busyCount == 0 && !permissionRequestInFlight
    binding.btnGrant.isEnabled = enabled
    binding.btnUpload.isEnabled = enabled
    binding.btnRefreshDashboard.isEnabled = enabled
  }

  private suspend fun <T> withBusyTask(block: suspend () -> T): T {
    beginBusy()
    return try {
      block()
    } finally {
      endBusy()
    }
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
    private const val PREFS_NAME = "health_uploader_prefs"
    private const val PREF_AUTO_PERMISSION_FLOW_STARTED = "auto_permission_flow_started"
    const val EXTRA_SKIP_AUTO_FLOW = "skip_auto_flow"

    val requiredPermissions = setOf(
      HealthPermission.getReadPermission(SleepSessionRecord::class),
      HealthPermission.getReadPermission(StepsRecord::class),
      HealthPermission.getReadPermission(ExerciseSessionRecord::class),
      HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
      HealthPermission.getReadPermission(DistanceRecord::class),
    )
  }
}
