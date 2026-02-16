package com.openclaw.healthuploader

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class SupabaseDashboardClient(
  private val httpClient: OkHttpClient = OkHttpClient(),
) {
  fun fetchLatest30(): List<HealthDailyRow> {
    val ingestEndpoint = BuildConfig.INGEST_ENDPOINT
    val apiKey = BuildConfig.INGEST_SECRET
    val baseUrl = deriveSupabaseBaseUrl(ingestEndpoint)

    if (baseUrl.isBlank() || apiKey.isBlank()) {
      throw IllegalStateException("INGEST_ENDPOINT 또는 INGEST_SECRET이 비어 있음")
    }

    val url = "$baseUrl/rest/v1/health_daily" +
      "?select=day,steps,distance_km,active_calories,workouts_count,sleep_duration_minutes" +
      "&order=day.desc&limit=30"

    val request = Request.Builder()
      .url(url)
      .addHeader("apikey", apiKey)
      .addHeader("Authorization", "Bearer $apiKey")
      .addHeader("Accept", "application/json")
      .build()

    httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IllegalStateException("대시보드 조회 실패: HTTP ${response.code}")
      }

      val raw = response.body?.string().orEmpty()
      val array = JSONArray(raw)
      return buildList {
        for (i in 0 until array.length()) {
          val row = array.getJSONObject(i)
          add(row.toHealthDailyRow())
        }
      }
    }
  }

  private fun deriveSupabaseBaseUrl(ingestEndpoint: String): String {
    // e.g. https://<ref>.functions.supabase.co/ingest-health -> https://<ref>.supabase.co
    val regex = Regex("^https://([a-z0-9-]+)\\.functions\\.supabase\\.co(?:/.*)?$")
    val match = regex.find(ingestEndpoint.trim()) ?: return ""
    val ref = match.groupValues[1]
    return "https://$ref.supabase.co"
  }

  private fun JSONObject.toHealthDailyRow(): HealthDailyRow {
    return HealthDailyRow(
      day = optString("day", "-"),
      steps = optNullableLong("steps"),
      distanceKm = optNullableDouble("distance_km"),
      activeCalories = optNullableDouble("active_calories"),
      workoutsCount = optNullableInt("workouts_count"),
      sleepDurationMinutes = optNullableInt("sleep_duration_minutes"),
    )
  }

  private fun JSONObject.optNullableLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return getLong(key)
  }

  private fun JSONObject.optNullableInt(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return getInt(key)
  }

  private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return getDouble(key)
  }
}
