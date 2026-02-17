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
    // Backwards compatible:
    // - Try v2 sleep columns first.
    // - If backend hasn't migrated yet (unknown columns), fallback to v1 select.
    val urlV2 = "$baseUrl/rest/v1/health_daily" +
      "?select=day," +
      "sleep_start,sleep_end," +
      "sleep_minutes,sleep_awake_minutes,sleep_light_minutes,sleep_deep_minutes,sleep_rem_minutes," +
      "sleep_score,sleep_avg_hr,sleep_spo2," +
      "sleep_duration_minutes," +
      "steps,distance_km,active_calories,workouts_count" +
      "&order=day.desc&limit=30"

    val urlV1 = "$baseUrl/rest/v1/health_daily" +
      "?select=day,steps,distance_km,active_calories,workouts_count,sleep_duration_minutes" +
      "&order=day.desc&limit=30"

    fun buildReq(url: String) = Request.Builder()
      .url(url)
      .addHeader("apikey", anonKey)
      .addHeader("Authorization", "Bearer $anonKey")
      .addHeader("Accept", "application/json")
      .get()
      .build()

    // Try v2 first.
    httpClient.newCall(buildReq(urlV2)).execute().use { response ->
      if (response.isSuccessful) {
        val rows = JSONArray(response.body?.string().orEmpty())
        return parseRows(rows)
      }

      // Common when columns don't exist yet: 400 Bad Request
      val code = response.code
      if (code != 400 && code != 404) {
        throw IllegalStateException("대시보드 조회 실패: HTTP $code")
      }
    }

    httpClient.newCall(buildReq(urlV1)).execute().use { response ->
      if (!response.isSuccessful) {
        throw IllegalStateException("대시보드 조회 실패(v1 fallback): HTTP ${response.code}")
      }
      val rows = JSONArray(response.body?.string().orEmpty())
      return parseRows(rows)
    }
  }

  private fun parseRows(rows: JSONArray): List<HealthDailyRow> = buildList {
    for (i in 0 until rows.length()) {
      val row = rows.getJSONObject(i)
      val sleepMinutes = row.optNullableInt("sleep_minutes") ?: row.optNullableInt("sleep_duration_minutes")
      add(
        HealthDailyRow(
          day = row.optString("day", "-"),
          sleepStart = row.optStringOrNull("sleep_start"),
          sleepEnd = row.optStringOrNull("sleep_end"),
          sleepMinutes = sleepMinutes,
          sleepAwakeMinutes = row.optNullableInt("sleep_awake_minutes"),
          sleepLightMinutes = row.optNullableInt("sleep_light_minutes"),
          sleepDeepMinutes = row.optNullableInt("sleep_deep_minutes"),
          sleepRemMinutes = row.optNullableInt("sleep_rem_minutes"),
          sleepScore = row.optNullableDouble("sleep_score"),
          sleepAvgHr = row.optNullableDouble("sleep_avg_hr"),
          sleepSpo2 = row.optNullableDouble("sleep_spo2"),
          sleepDurationMinutes = row.optNullableInt("sleep_duration_minutes"),
          steps = row.optNullableLong("steps"),
          distanceKm = row.optNullableDouble("distance_km"),
          activeCalories = row.optNullableDouble("active_calories"),
          workoutsCount = row.optNullableInt("workouts_count"),
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

  private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "").trim()
    return if (v.isBlank()) null else v
  }
}
