package com.openclaw.healthuploader

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

data class SleepStagesUiModel(
  val deepMin: Int,
  val remMin: Int,
  val lightMin: Int,
  val awakeMin: Int,
)

data class SleepSummaryUiModel(
  val totalText: String,
  val windowText: String,
  val qualityText: String,
  val stages: SleepStagesUiModel?,
  val stagesLineText: String,
  val insightText: String,
)

data class ActivitySummaryUiModel(
  val stepsText: String,
  val distanceText: String,
  val caloriesText: String,
  val workoutsText: String,
)

data class WeeklyInsightsUiModel(
  val avgSleepText: String,
  val efficiencyText: String,
  val regularityText: String,
  val debtText: String,
  val trendText: String,
  val dataText: String,
)

data class OptimalSleepUiModel(
  val targetDurationText: String,
  val bedtimeWindowText: String,
  val wakeWindowText: String,
  val confidenceText: String,
  val whyShort: String,
  val whyLong: String?,
)

class MainViewModel : ViewModel() {
  val healthConnectText = MutableLiveData("Health Connect: 확인 중")
  val permissionsText = MutableLiveData("권한: -")

  val statusText = MutableLiveData("대기 중")
  val actionsEnabled = MutableLiveData(true)

  // User preference (persisted via SharedPreferences)
  val targetSleepMinutes = MutableLiveData(8 * 60)

  val lastSyncedText = MutableLiveData("마지막 동기화: -")
  val sleepSummary = MutableLiveData(
    SleepSummaryUiModel(
      totalText = "—",
      windowText = "수면 구간: —",
      qualityText = "—",
      stages = null,
      stagesLineText = "데이터 없음",
      insightText = "—",
    )
  )
  val activitySummary = MutableLiveData(
    ActivitySummaryUiModel(
      stepsText = "—",
      distanceText = "—",
      caloriesText = "—",
      workoutsText = "—",
    )
  )

  val dashboardRows = MutableLiveData<List<HealthDailyRow>>(emptyList())
  val dashboardStateText = MutableLiveData("불러오는 중...")

  val weeklyInsights = MutableLiveData(
    WeeklyInsightsUiModel(
      avgSleepText = "평균 수면: —",
      efficiencyText = "수면 효율: —",
      regularityText = "규칙성: —",
      debtText = "수면 부채: —",
      trendText = "추세: —",
      dataText = "데이터: —",
    )
  )

  val optimalSleep = MutableLiveData(
    OptimalSleepUiModel(
      targetDurationText = "목표 수면: —",
      bedtimeWindowText = "취침 윈도우: —",
      wakeWindowText = "기상 윈도우: —",
      confidenceText = "신뢰도: —",
      whyShort = "왜 이렇게 추천?: —",
      whyLong = null,
    )
  )

  val snackbarEvent = MutableLiveData<Event<String>>()
}
