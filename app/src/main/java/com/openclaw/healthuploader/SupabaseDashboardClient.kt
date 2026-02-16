package com.openclaw.healthuploader

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class SupabaseDashboardClient(
  private val httpClient: OkHttpClient = OkHttpClient(),
) {
  fun fetchLatest30(): List<HealthDailyRow> {
    val ingestEndpoint = BuildConfig.INGEST_ENDPOINT.trim()
    val secret = BuildConfig.INGEST_SECRET.trim()

    if (ingestEndpoint.isBlank()) throw IllegalStateException("INGEST_ENDPOINT 누락")

    // 1) Try function GET first (new backend path)
    if (secret.isNotBlank()) {
      val byFunction = runCatching { fetchViaFunction(ingestEndpoint, secret) }.getOrNull()
      if (byFunction != null) return byFunction
    }

    // 2) Fallback to direct REST using anon key (for old backend path)
    val anon = BuildConfig.SUPABASE_ANON_KEY.trim()
    if (anon.isBlank()) {
      throw IllegalStateException("대시보드 인증 실패: SUPABASE_ANON_KEY 필요")
    }

    val baseUrl = deriveSupabaseBaseUrl(ingestEndpoint)
    if (baseUrl.isBlank()) throw IllegalStateException("Supabase URL 파싱 실패")

    return fetchViaRest(baseUrl, anon)
  }

  private fun fetchViaFunction(endpoint: String, secret: String): List<HealthDailyRow> {
    val request = Request.Builder()
      .url(endpoint)
      .addHeader("x-ingest-secret", secret)
      .addHeader("Accept", "application/json")
      .get()
      .build()

    httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IllegalStateException("function HTTP ${response.code}")
      val raw = response.body?.string().orEmpty()
      val rows = JSONObject(raw).optJSONArray("rows") ?: JSONArray()
      return parseRows(rows)
    }
  }

  private fun fetchViaRest(baseUrl: String, anonKey: String): List<HealthDailyRow> {
    val url = "$baseUrl/rest/v1/health_daily" +
      "?select=day,steps,distance_km,active_calories,workouts_count,sleep_duration_minutes" +
      "&order=day.desc&limit=30"

    val request = Request.Builder()
      .url(url)
      .addHeader("apikey", anonKey)
      .addHeader("Authorization", "Bearer $anonKey")
      .addHeader("Accept", "application/json")
      .get()
      .build()

    httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IllegalStateException("대시보드 조회 실패: HTTP ${response.code}")
      }
      val rows = JSONArray(response.body?.string().orEmpty())
      return parseRows(rows)
    }
  }

  private fun parseRows(rows: JSONArray): List<HealthDailyRow> = buildList {
    for (i in 0 until rows.length()) {
      val row = rows.getJSONObject(i)
      add(
        HealthDailyRow(
          day = row.optString("day", "-"),
          steps = row.optNullableLong("steps"),
          distanceKm = row.optNullableDouble("distance_km"),
          activeCalories = row.optNullableDouble("active_calories"),
          workoutsCount = row.optNullableInt("workouts_count"),
          sleepDurationMinutes = row.optNullableInt("sleep_duration_minutes"),
        )
      )
    }
  }

  private fun deriveSupabaseBaseUrl(ingestEndpoint: String): String {
    val endpoint = ingestEndpoint.trim().trimEnd('/')
    if (endpoint.isBlank()) return ""

    Regex("^https://([a-z0-9-]+)\\.functions\\.supabase\\.co(?:/.*)?$")
      .find(endpoint)
      ?.groupValues
      ?.getOrNull(1)
      ?.let { return "https://$it.supabase.co" }

    Regex("^https://([a-z0-9-]+)\\.supabase\\.co(?:/.*)?$")
      .find(endpoint)
      ?.groupValues
      ?.getOrNull(1)
      ?.let { return "https://$it.supabase.co" }

    return ""
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
