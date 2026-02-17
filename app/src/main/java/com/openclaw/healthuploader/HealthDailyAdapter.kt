package com.openclaw.healthuploader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class HealthDailyAdapter : RecyclerView.Adapter<HealthDailyAdapter.HealthDailyViewHolder>() {
  private val items = mutableListOf<HealthDailyRow>()

  fun submit(newItems: List<HealthDailyRow>) {
    items.clear()
    items.addAll(newItems)
    notifyDataSetChanged()
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HealthDailyViewHolder {
    val view = LayoutInflater.from(parent.context)
      .inflate(R.layout.item_health_daily, parent, false)
    return HealthDailyViewHolder(view)
  }

  override fun onBindViewHolder(holder: HealthDailyViewHolder, position: Int) {
    holder.bind(items[position])
  }

  override fun getItemCount(): Int = items.size

  class HealthDailyViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val tvDay: TextView = view.findViewById(R.id.tvDay)
    private val tvSleepTotal: TextView = view.findViewById(R.id.tvSleepTotal)
    private val tvSleepWindow: TextView = view.findViewById(R.id.tvSleepWindow)
    private val tvSleepStages: TextView = view.findViewById(R.id.tvSleepStages)
    private val tvActivitySecondary: TextView = view.findViewById(R.id.tvActivitySecondary)

    fun bind(row: HealthDailyRow) {
      tvDay.text = row.day

      val sleepMin = row.sleepMinutes ?: row.sleepDurationMinutes
      tvSleepTotal.text = sleepMin?.let { formatMinutes(it) } ?: "-"

      tvSleepWindow.text = formatSleepWindow(row.sleepStart, row.sleepEnd, row.sleepDurationMinutes)
        ?: "수면 구간: -"

      tvSleepStages.text = formatStages(
        awake = row.sleepAwakeMinutes,
        light = row.sleepLightMinutes,
        deep = row.sleepDeepMinutes,
        rem = row.sleepRemMinutes,
      ) ?: "수면 단계: -"

      tvActivitySecondary.text = buildString {
        val steps = row.steps?.toString() ?: "-"
        val dist = row.distanceKm?.let { String.format(Locale.US, "%.2fkm", it) } ?: "-km"
        val cal = row.activeCalories?.let { String.format(Locale.US, "%.0f", it) } ?: "-"
        val wo = row.workoutsCount?.toString() ?: "-"
        append("걸음 $steps · 거리 $dist · 칼로리 $cal · 운동 $wo")
      }
    }

    private fun formatMinutes(min: Int): String {
      val h = min / 60
      val m = min % 60
      return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }

    private fun formatSleepWindow(startIso: String?, endIso: String?, windowMin: Int?): String? {
      if (startIso.isNullOrBlank() || endIso.isNullOrBlank()) return null
      val zone = ZoneId.systemDefault()
      val start = runCatching { ZonedDateTime.ofInstant(Instant.parse(startIso), zone) }.getOrNull() ?: return null
      val end = runCatching { ZonedDateTime.ofInstant(Instant.parse(endIso), zone) }.getOrNull() ?: return null
      val w = windowMin?.let { formatMinutes(it) } ?: ""
      val range = String.format(Locale.US, "%02d:%02d ~ %02d:%02d", start.hour, start.minute, end.hour, end.minute)
      return if (w.isBlank()) range else "$range ($w)"
    }

    private fun formatStages(awake: Int?, light: Int?, deep: Int?, rem: Int?): String? {
      if (awake == null && light == null && deep == null && rem == null) return null
      val parts = mutableListOf<String>()
      if (deep != null) parts.add("깊 ${deep}m")
      if (rem != null) parts.add("렘 ${rem}m")
      if (light != null) parts.add("얕 ${light}m")
      if (awake != null) parts.add("깸 ${awake}m")
      return parts.joinToString(" · ")
    }
  }
}
