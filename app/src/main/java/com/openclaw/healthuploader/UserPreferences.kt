package com.openclaw.healthuploader

import android.content.Context
import android.content.SharedPreferences

object UserPreferences {
  const val PREFS_NAME = "health_uploader_prefs"

  private const val KEY_TARGET_SLEEP_MINUTES = "target_sleep_minutes"
  const val DEFAULT_TARGET_SLEEP_MINUTES: Int = 8 * 60

  fun prefs(ctx: Context): SharedPreferences {
    return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }

  fun getTargetSleepMinutes(prefs: SharedPreferences): Int {
    val v = prefs.getInt(KEY_TARGET_SLEEP_MINUTES, DEFAULT_TARGET_SLEEP_MINUTES)
    // Guard rails only; user target can be outside recommendation clamp.
    return v.coerceIn(4 * 60, 12 * 60)
  }

  fun setTargetSleepMinutes(prefs: SharedPreferences, minutes: Int) {
    prefs.edit().putInt(KEY_TARGET_SLEEP_MINUTES, minutes.coerceIn(4 * 60, 12 * 60)).apply()
  }
}

