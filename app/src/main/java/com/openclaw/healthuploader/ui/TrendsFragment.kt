package com.openclaw.healthuploader.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.openclaw.healthuploader.MainViewModel
import com.openclaw.healthuploader.R
import com.openclaw.healthuploader.databinding.FragmentTrendsBinding

class TrendsFragment : Fragment(R.layout.fragment_trends) {

  private var _binding: FragmentTrendsBinding? = null
  private val binding get() = _binding!!

  private val vm: MainViewModel by activityViewModels()

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    _binding = FragmentTrendsBinding.bind(view)

    vm.dashboardRows.observe(viewLifecycleOwner) { rows ->
      // NOTE: fragment_trends.xml no longer has tvWeeklySleep/tvWeeklyHint.
      // Keep this screen focused on charts + summaries.

      val periodDays = if (binding.togglePeriod.checkedButtonId == R.id.btnPeriod30) 30 else 7
      val slice = rows.take(periodDays)

      val sleepMins = slice.map { (it.sleepMinutes ?: it.sleepDurationMinutes) }
      val validSleep = sleepMins.filterNotNull().filter { it > 0 }

      if (validSleep.isEmpty()) {
        binding.tvDurationSummary.text = "데이터 없음"
        binding.tvTimeSummary.text = "대시보드 동기화 후 지난 ${periodDays}일 추세를 보여줄게"
        binding.chartDuration.setData(emptyList())
        binding.chartBedWake.setData(emptyList(), emptyList())
        return@observe
      }

      val avg = (validSleep.sum().toDouble() / validSleep.size).toInt()
      binding.tvDurationSummary.text = "평균: ${formatMinutes(avg)} · 합계: ${formatMinutes(validSleep.sum())}"

      // Duration bars (minutes)
      binding.chartDuration.setData(sleepMins.map { it })

      // Bed/Wake trends (minutes-of-day)
      fun minutesOfDayFromIso(iso: String?): Int? {
        if (iso.isNullOrBlank()) return null
        return try {
          val zdt = java.time.ZonedDateTime.parse(iso)
          zdt.hour * 60 + zdt.minute
        } catch (_: Exception) {
          try {
            val inst = java.time.Instant.parse(iso)
            val zdt = java.time.ZonedDateTime.ofInstant(inst, java.time.ZoneId.systemDefault())
            zdt.hour * 60 + zdt.minute
          } catch (_: Exception) {
            null
          }
        }
      }

      val bedtimes = slice.map { r -> minutesOfDayFromIso(r.sleepStart) }
      val wakes = slice.map { r -> minutesOfDayFromIso(r.sleepEnd) }
      binding.chartBedWake.setData(bedtimes, wakes)

      binding.tvTimeSummary.text = if (bedtimes.all { it == null } || wakes.all { it == null }) {
        "취침/기상 시각 데이터가 없어서 그래프를 그릴 수 없어"
      } else {
        "취침/기상 추세는 ‘시각’ 기준이라 자정 넘김(야간)도 포함해 보여줘"
      }
    }

    binding.togglePeriod.addOnButtonCheckedListener { _, checkedId, isChecked ->
      if (!isChecked) return@addOnButtonCheckedListener
      // Force rebind by re-setting the current rows.
      vm.dashboardRows.value?.let { vm.dashboardRows.postValue(it) }
    }
  }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }

  private fun formatMinutes(min: Int): String {
    val h = min / 60
    val m = min % 60
    return if (h > 0) "${h}시간 ${m}분" else "${m}분"
  }
}

