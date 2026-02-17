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

class MainViewModel : ViewModel() {
  val healthConnectText = MutableLiveData("Health Connect: 확인 중")
  val permissionsText = MutableLiveData("권한: -")

  val statusText = MutableLiveData("대기 중")
  val actionsEnabled = MutableLiveData(true)

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

  val snackbarEvent = MutableLiveData<Event<String>>()
}
