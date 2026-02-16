package com.openclaw.healthuploader

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    if (ingestEndpoint.isBlank()) {
      throw IllegalStateException("INGEST_ENDPOINT가 비어 있음")
    }
    if (apiKey.isBlank()) {
      throw IllegalStateException("INGEST_SECRET이 비어 있음")
    }
    if (baseUrl.isBlank()) {
      throw IllegalStateException("INGEST_ENDPOINT 형식이 올바르지 않음")
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
    val endpoint = ingestEndpoint.trim()
    if (endpoint.isBlank()) return ""

    val parsed = endpoint.toHttpUrlOrNull() ?: return ""
    if (parsed.scheme != "https") return ""

    val host = parsed.host.lowercase()
    val ref = when {
      host.endsWith(".functions.supabase.co") -> host.removeSuffix(".functions.supabase.co")
      host.endsWith(".supabase.co") -> host.removeSuffix(".supabase.co")
      else -> return ""
    }

    if (!SUPABASE_REF_REGEX.matches(ref)) return ""
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

  companion object {
    private val SUPABASE_REF_REGEX = Regex("^[a-z0-9-]+$")
  }
}
