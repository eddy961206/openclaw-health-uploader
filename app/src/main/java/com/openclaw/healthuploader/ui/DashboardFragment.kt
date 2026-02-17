package com.openclaw.healthuploader.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.openclaw.healthuploader.HealthDailyAdapter
import com.openclaw.healthuploader.MainViewModel
import com.openclaw.healthuploader.R
import com.openclaw.healthuploader.SleepStagesUiModel
import com.openclaw.healthuploader.databinding.FragmentDashboardBinding

class DashboardFragment : Fragment(R.layout.fragment_dashboard) {

  private var _binding: FragmentDashboardBinding? = null
  private val binding get() = _binding!!

  private val vm: MainViewModel by activityViewModels()
  private val adapter = HealthDailyAdapter()

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    _binding = FragmentDashboardBinding.bind(view)

    binding.rvRecent.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
    binding.rvRecent.adapter = adapter

    vm.lastSyncedText.observe(viewLifecycleOwner) { binding.tvLastSynced.text = it }
    vm.sleepSummary.observe(viewLifecycleOwner) { m ->
      binding.tvHeroTotal.text = m.totalText
      binding.tvHeroWindow.text = m.windowText
      binding.chipQuality.text = m.qualityText

      binding.tvStagesLine.text = m.stagesLineText
      updateStageBar(m.stages)

      binding.tvInsight.text = m.insightText
    }
    vm.activitySummary.observe(viewLifecycleOwner) { m ->
      binding.tvStepsValue.text = m.stepsText
      binding.tvDistanceValue.text = m.distanceText
      binding.tvCaloriesValue.text = m.caloriesText
      binding.tvWorkoutsValue.text = m.workoutsText
    }
    vm.dashboardRows.observe(viewLifecycleOwner) { rows ->
      adapter.submit(rows)
    }
    vm.dashboardStateText.observe(viewLifecycleOwner) { state ->
      val msg = state.orEmpty()
      val show = msg.isNotBlank()
      binding.tvRecentState.visibility = if (show) View.VISIBLE else View.GONE
      binding.tvRecentState.text = msg
    }
  }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }

  private fun updateStageBar(stages: SleepStagesUiModel?) {
    if (stages == null) {
      binding.stageBar.visibility = View.GONE
      binding.rowStages.visibility = View.GONE
      return
    }

    binding.stageBar.visibility = View.VISIBLE
    binding.rowStages.visibility = View.VISIBLE

    val total = (stages.deepMin + stages.remMin + stages.lightMin + stages.awakeMin).coerceAtLeast(1)

    fun setWeight(v: View, min: Int) {
      val lp = v.layoutParams as LinearLayout.LayoutParams
      lp.weight = (min.toFloat() / total.toFloat()).coerceAtLeast(0f)
      v.layoutParams = lp
      v.visibility = if (min > 0) View.VISIBLE else View.INVISIBLE
    }

    setWeight(binding.segDeep, stages.deepMin)
    setWeight(binding.segRem, stages.remMin)
    setWeight(binding.segLight, stages.lightMin)
    setWeight(binding.segAwake, stages.awakeMin)

    binding.tvDeepValue.text = "${stages.deepMin}분"
    binding.tvRemValue.text = "${stages.remMin}분"
    binding.tvLightValue.text = "${stages.lightMin}분"
    binding.tvAwakeValue.text = "${stages.awakeMin}분"
  }
}

