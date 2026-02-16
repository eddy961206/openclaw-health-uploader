package com.openclaw.healthuploader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
      tvDay.text = row.day

      val steps = row.steps?.toString() ?: "-"
      val distance = row.distanceKm?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
      val calories = row.activeCalories?.let { String.format(Locale.US, "%.0f", it) } ?: "-"

      tvSummary.text = "걸음 $steps | 거리 ${distance}km | 칼로리 ${calories}kcal"

      val workouts = row.workoutsCount?.toString() ?: "-"
      val sleep = row.sleepDurationMinutes?.toString() ?: "-"
      tvMeta.text = "운동 ${workouts}회 | 수면 ${sleep}분"
    }
  }
}
