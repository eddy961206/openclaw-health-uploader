package com.openclaw.healthuploader.ui

import android.os.Bundle
import android.view.View
import kotlin.math.roundToInt
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.openclaw.healthuploader.UserPreferences
import com.openclaw.healthuploader.BuildConfig
import com.openclaw.healthuploader.MainActivity
import com.openclaw.healthuploader.MainViewModel
import com.openclaw.healthuploader.R
import com.openclaw.healthuploader.analytics.SleepInsightsAnalytics
import com.openclaw.healthuploader.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment(R.layout.fragment_settings) {

  private var _binding: FragmentSettingsBinding? = null
  private val binding get() = _binding!!

  private val vm: MainViewModel by activityViewModels()

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    _binding = FragmentSettingsBinding.bind(view)

    val prefs = UserPreferences.prefs(requireContext())

    fun roundToStep(min: Int, step: Int): Int {
      if (step <= 0) return min
      return ((min + (step / 2)) / step) * step
    }

    // Target sleep minutes (persisted)
    val initialTarget = roundToStep(UserPreferences.getTargetSleepMinutes(prefs), step = 15)
    if (vm.targetSleepMinutes.value == null) vm.targetSleepMinutes.value = initialTarget

    binding.sliderTargetSleep.value = initialTarget.toFloat()
    binding.tvTargetSleepValue.text = SleepInsightsAnalytics.formatMinutesKo(initialTarget)

    vm.targetSleepMinutes.observe(viewLifecycleOwner) { raw ->
      val v = roundToStep(raw ?: UserPreferences.DEFAULT_TARGET_SLEEP_MINUTES, step = 15)
      if (binding.sliderTargetSleep.value.roundToInt() != v) {
        binding.sliderTargetSleep.value = v.toFloat()
      }
      binding.tvTargetSleepValue.text = SleepInsightsAnalytics.formatMinutesKo(v)
    }

    binding.sliderTargetSleep.addOnChangeListener { _, value, fromUser ->
      val v = value.roundToInt()
      binding.tvTargetSleepValue.text = SleepInsightsAnalytics.formatMinutesKo(v)
      if (!fromUser) return@addOnChangeListener
      UserPreferences.setTargetSleepMinutes(prefs, v)
      vm.targetSleepMinutes.value = v
    }

    vm.healthConnectText.observe(viewLifecycleOwner) { binding.tvHealthConnect.text = it }
    vm.permissionsText.observe(viewLifecycleOwner) { binding.tvPermissions.text = it }
    vm.statusText.observe(viewLifecycleOwner) { binding.tvStatus.text = it }
    vm.actionsEnabled.observe(viewLifecycleOwner) { enabled ->
      binding.btnGrant.isEnabled = enabled
      binding.btnUploadYesterday.isEnabled = enabled
      binding.btnSyncNow.isEnabled = enabled
    }

    binding.tvEndpoint.text = if (BuildConfig.INGEST_ENDPOINT.isNotBlank()) "설정됨" else "미설정"
    binding.tvSecret.text = if (BuildConfig.INGEST_SECRET.isNotBlank()) "설정됨" else "미설정"
    binding.tvAnon.text = if (BuildConfig.SUPABASE_ANON_KEY.isNotBlank()) "설정됨" else "미설정"

    binding.btnGrant.setOnClickListener { (activity as? MainActivity)?.onGrantClicked() }
    binding.btnUploadYesterday.setOnClickListener { (activity as? MainActivity)?.onUploadYesterdayClicked() }
    binding.btnSyncNow.setOnClickListener { (activity as? MainActivity)?.onSyncClicked() }
  }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
}
