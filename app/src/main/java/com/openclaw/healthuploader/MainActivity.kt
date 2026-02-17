package com.openclaw.healthuploader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
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
import java.util.Locale

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
    renderSleepSummaryLoading("대기 중...")

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

    // Local sleep-first summary (doesn't rely on backend schema migration).
    runCatching { refreshLocalSleepSummary() }.onFailure {
      Log.d(TAG, "local sleep summary failed: ${it.message}")
    }

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
      // v2 sleep fields are kept under source.sleep_v2 by default for backwards compatibility.
      val sleepV2 = JSONObject().apply {
        putIfNotNull("sleep_minutes", sleep.sleepMinutes)
        putIfNotNull("sleep_awake_minutes", sleep.awakeMinutes)
        putIfNotNull("sleep_light_minutes", sleep.lightMinutes)
        putIfNotNull("sleep_deep_minutes", sleep.deepMinutes)
        putIfNotNull("sleep_rem_minutes", sleep.remMinutes)
        putIfNotNull("sleep_score", sleep.sleepScore)
        putIfNotNull("sleep_avg_hr", sleep.avgHr)
        putIfNotNull("sleep_spo2", sleep.spo2)
        putIfNotNull("stage_records", sleep.debug.stageCount)
        if (sleep.debug.stageTypes.isNotEmpty()) {
          put("stage_types", sleep.debug.stageTypes.joinToString(","))
        }
      }

      // activity
      put("steps", steps)
      put("active_calories", activeCalories)
      put("workouts_count", workouts.size)
      put("distance_km", distanceKm)

      // metadata
      put("source", JSONObject().apply {
        put("tz", zone.id)
        put("collected_at", ZonedDateTime.now(zone).toInstant().toString())
        put("note", "v0.9 sleep-first aggregates")
        put("sleep_v2", sleepV2)
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
    private const val TAG = "MainActivity"
    private const val PREFS_NAME = "health_uploader_prefs"
    private const val PREF_AUTO_PERMISSION_FLOW_STARTED = "auto_permission_flow_started"
    const val EXTRA_SKIP_AUTO_FLOW = "skip_auto_flow"

    val permSleepSession: String = HealthPermission.getReadPermission(SleepSessionRecord::class)
    val permHeartRate: String = HealthPermission.getReadPermission(HeartRateRecord::class)

    val requiredPermissions = setOf(
      permSleepSession,
      HealthPermission.getReadPermission(StepsRecord::class),
      HealthPermission.getReadPermission(ExerciseSessionRecord::class),
      HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
      HealthPermission.getReadPermission(DistanceRecord::class),
    )
  }

  private fun renderSleepSummaryLoading(message: String) {
    binding.tvSleepSummaryTotal.text = message
    binding.tvSleepSummaryWindow.text = "수면 구간: -"
    binding.tvSleepSummaryStages.text = "수면 단계: -"
    binding.tvSleepSummaryInsight.text = "인사이트: -"
  }

  private suspend fun refreshLocalSleepSummary() {
    val status = HealthConnectClient.getSdkStatus(this)
    if (status != HealthConnectClient.SDK_AVAILABLE) {
      binding.tvSleepSummaryTotal.text = "Health Connect 필요"
      binding.tvSleepSummaryWindow.text = "수면 구간: -"
      binding.tvSleepSummaryStages.text = "수면 단계: -"
      binding.tvSleepSummaryInsight.text = "인사이트: Health Connect 설치/권한이 필요해"
      return
    }

    val zone = ZoneId.systemDefault()
    val day = LocalDate.now(zone).minusDays(1)
    binding.tvSleepSummaryTotal.text = "집계 중..."

    val (sleep, bedtimeHint) = withContext(Dispatchers.IO) {
      val client = HealthConnectClient.getOrCreate(this@MainActivity)
      val granted = client.permissionController.getGrantedPermissions()
      val s = SleepDailyCollector.collectForDay(
        client = client,
        day = day,
        zone = zone,
        grantedPermissions = granted,
        enableSleepVitals = true,
      )
      val hint = runCatching { SleepDailyCollector.collectBedtimeConsistencyHint(client, zone, days = 7) }.getOrNull()
      Pair(s, hint)
    }

    val totalMin = sleep.sleepMinutes
    val windowMin = sleep.sleepWindowMinutes

    binding.tvSleepSummaryTotal.text = "총 수면: ${totalMin?.let { formatMinutes(it) } ?: "-"}"
    binding.tvSleepSummaryWindow.text = formatSleepWindowLine(sleep.sleepStart, sleep.sleepEnd, windowMin)
    binding.tvSleepSummaryStages.text = formatStagesLine(sleep.awakeMinutes, sleep.lightMinutes, sleep.deepMinutes, sleep.remMinutes)
    binding.tvSleepSummaryInsight.text = buildInsightLine(sleep, bedtimeHint)
  }

  private fun formatMinutes(min: Int): String {
    val h = min / 60
    val m = min % 60
    return if (h > 0) "${h}시간 ${m}분" else "${m}분"
  }

  private fun formatSleepWindowLine(start: Instant?, end: Instant?, windowMin: Int?): String {
    if (start == null || end == null) return "수면 구간: -"
    val zone = ZoneId.systemDefault()
    val zs = ZonedDateTime.ofInstant(start, zone)
    val ze = ZonedDateTime.ofInstant(end, zone)
    val range = String.format(Locale.US, "%02d:%02d ~ %02d:%02d", zs.hour, zs.minute, ze.hour, ze.minute)
    val w = windowMin?.let { formatMinutes(it) } ?: "-"
    return "수면 구간: $range ($w)"
  }

  private fun formatStagesLine(awake: Int?, light: Int?, deep: Int?, rem: Int?): String {
    if (awake == null && light == null && deep == null && rem == null) {
      return "수면 단계: (단계 데이터 없음)"
    }
    val parts = mutableListOf<String>()
    if (deep != null) parts.add("깊 ${formatMinutes(deep)}")
    if (rem != null) parts.add("렘 ${formatMinutes(rem)}")
    if (light != null) parts.add("얕 ${formatMinutes(light)}")
    if (awake != null) parts.add("깸 ${formatMinutes(awake)}")
    return "수면 단계: " + parts.joinToString(" · ")
  }

  private fun buildInsightLine(sleep: SleepDailyCollector.SleepDaily, bedtimeHint: String?): String {
    val parts = mutableListOf<String>()

    val sleepMin = sleep.sleepMinutes
    val deep = sleep.deepMinutes
    val rem = sleep.remMinutes
    if (sleepMin != null && sleepMin > 0 && deep != null && rem != null) {
      val deepPct = deep.toDouble() / sleepMin.toDouble()
      val remPct = rem.toDouble() / sleepMin.toDouble()
      when {
        deepPct < 0.10 -> parts.add("깊은 수면 비율이 낮은 편이야")
        deepPct > 0.35 -> parts.add("깊은 수면 비율이 높은 편이야")
      }
      when {
        remPct < 0.15 -> parts.add("렘 수면 비율이 낮은 편이야")
        remPct > 0.35 -> parts.add("렘 수면 비율이 높은 편이야")
      }
      if (parts.isEmpty()) parts.add("단계 밸런스가 무난해 보여")
    } else if (sleep.sleepMinutes != null) {
      parts.add("수면 단계가 없어 총 수면만 기준으로 보여줘")
    } else {
      parts.add("어제 수면 기록이 없거나 Health Connect에서 못 읽었어")
    }

    if (!bedtimeHint.isNullOrBlank()) parts.add(bedtimeHint)

    val hr = sleep.avgHr
    if (hr != null) parts.add("수면 중 평균 심박 ${String.format(Locale.US, "%.0f", hr)}bpm")

    return "인사이트: " + parts.joinToString(" · ")
  }
}
