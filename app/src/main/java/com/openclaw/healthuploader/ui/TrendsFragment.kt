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
      val last7 = rows.take(7)
      val totalMin = last7.sumOf { (it.sleepMinutes ?: it.sleepDurationMinutes) ?: 0 }
      if (totalMin <= 0) {
        binding.tvWeeklySleep.text = "데이터 없음"
        binding.tvWeeklyHint.text = "대시보드 동기화 후 지난 7일 합계를 보여줄게"
      } else {
        binding.tvWeeklySleep.text = formatMinutes(totalMin)
        binding.tvWeeklyHint.text = "지난 7일 총 수면(대시보드 기준)이야"
      }
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

