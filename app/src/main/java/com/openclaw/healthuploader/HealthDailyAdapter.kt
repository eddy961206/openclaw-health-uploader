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
    private val tvSteps: TextView = view.findViewById(R.id.tvSteps)
    private val tvDistance: TextView = view.findViewById(R.id.tvDistance)
    private val tvCalories: TextView = view.findViewById(R.id.tvCalories)
    private val tvWorkouts: TextView = view.findViewById(R.id.tvWorkouts)
    private val tvSleep: TextView = view.findViewById(R.id.tvSleep)

    fun bind(row: HealthDailyRow) {
      tvDay.text = row.day
      tvSteps.text = row.steps?.toString() ?: "-"
      tvDistance.text = row.distanceKm?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
      tvCalories.text = row.activeCalories?.let { String.format(Locale.US, "%.0f", it) } ?: "-"
      tvWorkouts.text = row.workoutsCount?.toString() ?: "-"
      tvSleep.text = row.sleepDurationMinutes?.toString() ?: "-"
    }
  }
}
