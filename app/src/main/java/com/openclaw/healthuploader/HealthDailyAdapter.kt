package com.openclaw.healthuploader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.NumberFormat
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
    private val tvSummary: TextView = view.findViewById(R.id.tvSummary)
    private val tvMeta: TextView = view.findViewById(R.id.tvMeta)

    fun bind(row: HealthDailyRow) {
      tvDay.text = "날짜: ${row.day}"

      val intFmt = NumberFormat.getIntegerInstance(Locale.KOREA)

      val sleep = row.sleepDurationMinutes?.let { "${intFmt.format(it)}분" } ?: "-"
      val steps = row.steps?.let { intFmt.format(it) } ?: "-"
      val distance = row.distanceKm?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
      val calories = row.activeCalories?.let { intFmt.format(it.toInt()) } ?: "-"
      val workouts = row.workoutsCount?.let { intFmt.format(it) } ?: "-"

      tvSummary.text = "수면 $sleep | 걸음 $steps | 거리 ${distance}km"
      tvMeta.text = "활동칼로리 ${calories}kcal | 운동 ${workouts}회"
    }
  }
}
