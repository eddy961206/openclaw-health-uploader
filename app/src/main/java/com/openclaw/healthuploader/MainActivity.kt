package com.openclaw.healthuploader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
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
import com.openclaw.healthuploader.analytics.SleepInsightsAnalytics
import com.openclaw.healthuploader.databinding.ActivityMainBinding
import com.openclaw.healthuploader.ui.DashboardFragment
import com.openclaw.healthuploader.ui.SettingsFragment
import com.openclaw.healthuploader.ui.TrendsFragment
import com.google.android.material.snackbar.Snackbar
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
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

  private lateinit var binding: ActivityMainBinding
  private val uiScope = CoroutineScope(Dispatchers.Main)
  private lateinit var vm: MainViewModel
  private val dashboardClient = SupabaseDashboardClient()
  private val prefs by lazy { UserPreferences.prefs(this) }

  private val permissions = requiredPermissions

  private var busyCount = 0
  private var permissionRequestInFlight = false
  private var pendingHealthConnectInstallCheck = false

  private enum class Tab { DASHBOARD, TRENDS, SETTINGS }
  private var currentTab: Tab = Tab.DASHBOARD

  private val requestPermissions = registerForActivityResult(
    PermissionController.createRequestPermissionResultContract()
  ) { granted ->
    uiScope.launch {
      permissionRequestInFlight = false
      updateActionButtonsState()
      runCatching { updateHealthConnectUi() }

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

    vm = ViewModelProvider(this)[MainViewModel::class.java]
    setSupportActionBar(binding.topAppBar)

    renderSleepSummaryLoading("대기 중...")
    setupBottomNav(savedInstanceState)

    // Load persisted preference and keep insights derived from fetched rows up-to-date.
    vm.targetSleepMinutes.value = UserPreferences.getTargetSleepMinutes(prefs)
    vm.dashboardRows.observe(this) { recomputeSleepInsights(it, vm.targetSleepMinutes.value ?: UserPreferences.DEFAULT_TARGET_SLEEP_MINUTES) }
    vm.targetSleepMinutes.observe(this) { recomputeSleepInsights(vm.dashboardRows.value.orEmpty(), it ?: UserPreferences.DEFAULT_TARGET_SLEEP_MINUTES) }

    vm.snackbarEvent.observe(this) { e ->
      val msg = e?.getContentIfNotHandled() ?: return@observe
      Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
    }

    uiScope.launch {
      runCatching { updateHealthConnectUi() }
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

  override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
    menuInflater.inflate(R.menu.menu_dashboard_top_app_bar, menu)
    return true
  }

  override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
    val sync = menu.findItem(R.id.action_sync)
    sync.isVisible = currentTab == Tab.DASHBOARD
    sync.isEnabled = vm.actionsEnabled.value == true
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
    return when (item.itemId) {
      R.id.action_sync -> {
        onSyncClicked()
        true
      }
      else -> super.onOptionsItemSelected(item)
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
    vm.statusText.value = msg
  }

  private suspend fun ensureHealthConnectAndPermissions(
    autoFlow: Boolean,
    openInstallIfMissing: Boolean,
  ): Boolean {
    val status = HealthConnectClient.getSdkStatus(this)
    if (status != HealthConnectClient.SDK_AVAILABLE) {
      vm.healthConnectText.value = "Health Connect: 필요"
      vm.permissionsText.value = "권한: -"
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
    vm.healthConnectText.value = "Health Connect: 사용 가능"
    vm.permissionsText.value = "권한: ${granted.intersect(permissions).size}/${permissions.size}"
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

  private suspend fun updateHealthConnectUi() {
    val status = HealthConnectClient.getSdkStatus(this)
    if (status != HealthConnectClient.SDK_AVAILABLE) {
      vm.healthConnectText.value = "Health Connect: 필요"
      vm.permissionsText.value = "권한: -"
      return
    }
    val client = HealthConnectClient.getOrCreate(this)
    val granted = withContext(Dispatchers.IO) { client.permissionController.getGrantedPermissions() }
    vm.healthConnectText.value = "Health Connect: 사용 가능"
    vm.permissionsText.value = "권한: ${granted.intersect(permissions).size}/${permissions.size}"
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
      vm.snackbarEvent.value = Event("$reason 실패: 집계 오류")
      return
    }

    updateStatus("$reason: 업로드 중 ($day)")
    val uploaded = withContext(Dispatchers.IO) {
      runCatching { postToSupabase(payload.getOrThrow()) }.getOrDefault(false)
    }

    updateStatus(if (uploaded) "$reason 성공: $day" else "$reason 실패: $day")
    if (!uploaded) vm.snackbarEvent.value = Event("$reason 실패: 업로드 오류")
  }

  private suspend fun refreshDashboard() {
    vm.dashboardStateText.value = "대시보드 불러오는 중..."

    // Local sleep-first summary (doesn't rely on backend schema migration).
    runCatching { refreshLocalSleepSummary() }.onFailure {
      Log.d(TAG, "local sleep summary failed: ${it.message}")
    }

    val result = withContext(Dispatchers.IO) {
      runCatching { dashboardClient.fetchLatest30() }
    }

    result.onSuccess { rows ->
      if (rows.isEmpty()) {
        vm.dashboardRows.value = emptyList()
        vm.dashboardStateText.value = "데이터 없음"
      } else {
        vm.dashboardRows.value = rows
        vm.dashboardStateText.value = ""
      }

      val now = ZonedDateTime.now(ZoneId.systemDefault())
      vm.lastSyncedText.value = String.format(Locale.US, "마지막 동기화: %02d:%02d", now.hour, now.minute)
    }.onFailure { e ->
      vm.dashboardRows.value = emptyList()
      vm.dashboardStateText.value = "대시보드 오류: ${e.message ?: "알 수 없는 오류"}"
      vm.snackbarEvent.value = Event("대시보드 동기화 실패: ${e.message ?: "알 수 없는 오류"}")
    }
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
    vm.actionsEnabled.value = enabled
    invalidateOptionsMenu()
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
    vm.sleepSummary.value = SleepSummaryUiModel(
      totalText = message,
      windowText = "수면 구간: —",
      qualityText = "—",
      stages = null,
      stagesLineText = "데이터 없음",
      insightText = "—",
    )
  }

  private suspend fun refreshLocalSleepSummary() {
    val status = HealthConnectClient.getSdkStatus(this)
    if (status != HealthConnectClient.SDK_AVAILABLE) {
      vm.sleepSummary.value = SleepSummaryUiModel(
        totalText = "Health Connect 필요",
        windowText = "수면 구간: —",
        qualityText = "설치/권한 필요",
        stages = null,
        stagesLineText = "데이터 없음",
        insightText = "Health Connect 설치/권한이 필요해",
      )
      vm.activitySummary.value = ActivitySummaryUiModel("—", "—", "—", "—")
      return
    }

    val zone = ZoneId.systemDefault()
    val day = LocalDate.now(zone).minusDays(1)
    vm.sleepSummary.value = (vm.sleepSummary.value ?: SleepSummaryUiModel(
      totalText = "—",
      windowText = "수면 구간: —",
      qualityText = "—",
      stages = null,
      stagesLineText = "데이터 없음",
      insightText = "—",
    )).copy(totalText = "집계 중...")

    val (sleep, bedtimeHint, activity) = withContext(Dispatchers.IO) {
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

      val (start, end) = dayWindow(day, zone)
      val steps = runCatching {
        client.aggregate(
          AggregateRequest(
            metrics = setOf(StepsRecord.COUNT_TOTAL),
            timeRangeFilter = TimeRangeFilter.between(start, end)
          )
        )[StepsRecord.COUNT_TOTAL]?.toLong()
      }.getOrNull()

      val activeCalories = runCatching {
        client.aggregate(
          AggregateRequest(
            metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
            timeRangeFilter = TimeRangeFilter.between(start, end)
          )
        )[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
      }.getOrNull()

      val distanceKm = runCatching {
        client.aggregate(
          AggregateRequest(
            metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
            timeRangeFilter = TimeRangeFilter.between(start, end)
          )
        )[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.div(1000.0)
      }.getOrNull()

      val workoutsCount = runCatching {
        client.readRecords(
          androidx.health.connect.client.request.ReadRecordsRequest(
            ExerciseSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(start, end),
          )
        ).records.size
      }.getOrDefault(0)

      Triple(s, hint, ActivitySummaryUiModel(
        stepsText = steps?.toString() ?: "—",
        distanceText = distanceKm?.let { String.format(Locale.US, "%.2fkm", it) } ?: "—",
        caloriesText = activeCalories?.let { String.format(Locale.US, "%.0f kcal", it) } ?: "—",
        workoutsText = workoutsCount.toString(),
      ))
    }

    val totalMin = sleep.sleepMinutes
    val windowMin = sleep.sleepWindowMinutes

    val totalText = totalMin?.let { formatMinutes(it) } ?: "데이터 없음"
    val windowText = formatSleepWindowLine(sleep.sleepStart, sleep.sleepEnd, windowMin)
    val stagesLine = formatStagesLine(sleep.awakeMinutes, sleep.lightMinutes, sleep.deepMinutes, sleep.remMinutes)
    val insight = buildInsightLine(sleep, bedtimeHint).removePrefix("인사이트: ").trim()

    val quality = when (totalMin ?: 0) {
      in 0..359 -> "짧아"
      in 360..419 -> "보통"
      else -> "충분해"
    }

    val stagesUi =
      if (sleep.deepMinutes != null && sleep.remMinutes != null && sleep.lightMinutes != null && sleep.awakeMinutes != null) {
        SleepStagesUiModel(
          deepMin = sleep.deepMinutes,
          remMin = sleep.remMinutes,
          lightMin = sleep.lightMinutes,
          awakeMin = sleep.awakeMinutes,
        )
      } else null

    vm.sleepSummary.value = SleepSummaryUiModel(
      totalText = totalText,
      windowText = windowText,
      qualityText = quality,
      stages = stagesUi,
      stagesLineText = if (stagesUi == null) "데이터 없음" else stagesLine.removePrefix("수면 단계: ").trim(),
      insightText = insight,
    )
    vm.activitySummary.value = activity
  }

  private fun formatMinutes(min: Int): String {
    val h = min / 60
    val m = min % 60
    return if (h > 0) "${h}시간 ${m}분" else "${m}분"
  }

  private fun recomputeSleepInsights(rows: List<HealthDailyRow>, targetSleepMin: Int) {
    val zone = ZoneId.systemDefault()
    val result = SleepInsightsAnalytics.compute(rows = rows, zone = zone, targetSleepMin = targetSleepMin)

    val weekly = result.weekly
    val avgSleep = weekly.avgSleepMin?.let { SleepInsightsAnalytics.formatMinutesKo(it) } ?: "데이터 없음"
    val eff = weekly.avgEfficiencyPct?.let { "${it}%" } ?: "-"

    val reg = if (weekly.regularityScore != null && weekly.bedtimeStdDevMin != null && weekly.wakeStdDevMin != null) {
      val b = weekly.bedtimeStdDevMin.roundToInt()
      val w = weekly.wakeStdDevMin.roundToInt()
      "${weekly.regularityScore}점 (취침±${b}분, 기상±${w}분)"
    } else {
      "데이터 부족"
    }

    val debt = weekly.sleepDebtMin?.let { SleepInsightsAnalytics.formatMinutesKo(it) } ?: "-"

    fun slopeText(label: String, slope: Double?): String {
      if (slope == null) return "$label -"
      val rounded = slope.roundToInt()
      if (abs(rounded) <= 1) return "$label 보합"
      val sign = if (rounded > 0) "+" else ""
      return "$label ${sign}${rounded}분/일"
    }

    val trendLine = buildString {
      append("추세(7일): ")
      append(slopeText("수면", weekly.durationSlopeMinPerDay))
      append(", ")
      append(slopeText("취침", weekly.bedtimeSlopeMinPerDay))
      append(", ")
      append(slopeText("기상", weekly.wakeSlopeMinPerDay))
    }

    vm.weeklyInsights.value = WeeklyInsightsUiModel(
      avgSleepText = "평균 수면: $avgSleep",
      efficiencyText = "수면 효율: $eff",
      regularityText = "규칙성: $reg",
      debtText = "수면 부채(목표 ${SleepInsightsAnalytics.formatMinutesKo(targetSleepMin)}): $debt",
      trendText = trendLine,
      dataText = "데이터: ${weekly.daysWithSleep}/7일 기준",
    )

    val rec = result.recommendation
    val targetText = rec.targetMin?.let { SleepInsightsAnalytics.formatMinutesKo(it) } ?: "—"
    val bedtimeText = rec.bedtimeWindow?.let { SleepInsightsAnalytics.formatWindow(it) } ?: "—"
    val wakeText = rec.wakeWindow?.let { SleepInsightsAnalytics.formatWindow(it) } ?: "—"

    val confidenceText = when (rec.confidence) {
      SleepInsightsAnalytics.ConfidenceLevel.HIGH -> "높음"
      SleepInsightsAnalytics.ConfidenceLevel.MID -> "중간"
      SleepInsightsAnalytics.ConfidenceLevel.LOW -> "낮음"
    }

    vm.optimalSleep.value = OptimalSleepUiModel(
      targetDurationText = "목표 수면: $targetText",
      bedtimeWindowText = "취침 윈도우: $bedtimeText",
      wakeWindowText = "기상 윈도우: $wakeText",
      confidenceText = "신뢰도: $confidenceText",
      whyShort = rec.whyShort,
      whyLong = rec.whyLong,
    )
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

  private fun setupBottomNav(savedInstanceState: Bundle?) {
    if (savedInstanceState == null) {
      selectTab(Tab.DASHBOARD, pushToBackStack = false)
      binding.bottomNav.selectedItemId = R.id.nav_dashboard
    } else {
      // Keep the fragment manager state; just ensure toolbar title is correct.
      currentTab = when (binding.bottomNav.selectedItemId) {
        R.id.nav_trends -> Tab.TRENDS
        R.id.nav_settings -> Tab.SETTINGS
        else -> Tab.DASHBOARD
      }
      applyTopBarForTab(currentTab)
      invalidateOptionsMenu()
    }

    binding.bottomNav.setOnItemSelectedListener { item ->
      when (item.itemId) {
        R.id.nav_dashboard -> {
          selectTab(Tab.DASHBOARD, pushToBackStack = false)
          true
        }
        R.id.nav_trends -> {
          selectTab(Tab.TRENDS, pushToBackStack = false)
          true
        }
        R.id.nav_settings -> {
          selectTab(Tab.SETTINGS, pushToBackStack = false)
          true
        }
        else -> false
      }
    }
  }

  private fun selectTab(tab: Tab, pushToBackStack: Boolean) {
    currentTab = tab
    applyTopBarForTab(tab)
    invalidateOptionsMenu()

    val tag = when (tab) {
      Tab.DASHBOARD -> "dashboard"
      Tab.TRENDS -> "trends"
      Tab.SETTINGS -> "settings"
    }

    val fragment = supportFragmentManager.findFragmentByTag(tag) ?: when (tab) {
      Tab.DASHBOARD -> DashboardFragment()
      Tab.TRENDS -> TrendsFragment()
      Tab.SETTINGS -> SettingsFragment()
    }

    val tx = supportFragmentManager.beginTransaction()

    fun hideIfExists(t: String) {
      supportFragmentManager.findFragmentByTag(t)?.let { tx.hide(it) }
    }
    hideIfExists("dashboard")
    hideIfExists("trends")
    hideIfExists("settings")

    if (fragment.isAdded) {
      tx.show(fragment)
    } else {
      tx.add(binding.fragmentContainer.id, fragment, tag)
    }

    if (pushToBackStack) tx.addToBackStack(tag)
    tx.commit()
  }

  private fun applyTopBarForTab(tab: Tab) {
    binding.topAppBar.title = when (tab) {
      Tab.DASHBOARD -> "오늘의 수면"
      Tab.TRENDS -> "트렌드"
      Tab.SETTINGS -> "설정"
    }
  }

  fun onGrantClicked() {
    uiScope.launch {
      withBusyTask {
        val granted = ensureHealthConnectAndPermissions(autoFlow = false, openInstallIfMissing = true)
        if (granted) onPermissionsReady()
      }
    }
  }

  fun onUploadYesterdayClicked() {
    uiScope.launch {
      withBusyTask {
        val granted = ensureHealthConnectAndPermissions(autoFlow = false, openInstallIfMissing = true)
        if (!granted) return@withBusyTask

        val day = LocalDate.now(ZoneId.systemDefault()).minusDays(1)
        uploadSingleDay(day, "수동 업로드")
      }
    }
  }

  fun onSyncClicked() {
    uiScope.launch {
      withBusyTask {
        refreshDashboard()
      }
    }
  }
}
