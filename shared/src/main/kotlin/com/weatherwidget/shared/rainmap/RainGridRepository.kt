package com.weatherwidget.shared.rainmap

import com.weatherwidget.shared.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.Locale

private const val TAG = "RainGrid"

/**
 * Loads the Open-Meteo rain grid for a centre point: **one** multi-location `/v1/forecast` request
 * (25 KB gzipped for 15×15 × 48 h, measured 2026-10-20), cached on disk for [MAX_AGE_MS] so
 * reopening the viewer within the hour costs nothing. Nothing fetches it in the background.
 *
 * Same model as the app's Open-Meteo forecast (no `models=`), so the centre cell matches the graph.
 */
class RainGridRepository(
    private val httpClient: HttpClient,
    private val cacheDir: File,
    private val defaultSpec: RainGridSpec = RainGridSpec.DEFAULT,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** [spec] picks the zoom; each zoom is cached on its own, so switching back costs nothing. */
    suspend fun load(centerLat: Double, centerLon: Double, spec: RainGridSpec = defaultSpec): Result<RainGrid> = runCatching {
        val lats = spec.latitudes(centerLat)
        val lons = spec.longitudes(centerLat, centerLon)
        val file = File(cacheDir, cacheName(centerLat, centerLon, spec))
        val now = nowMs()
        val cached = file.takeIf { it.exists() && now - it.lastModified() in 0 until MAX_AGE_MS }
        val text: String
        val fetchedAt: Long
        if (cached != null) {
            text = cached.readText()
            fetchedAt = cached.lastModified()
            Log.d(TAG, "RAIN_GRID outcome=cached ageMin=${(now - fetchedAt) / 60_000}")
        } else {
            text = fetch(lats, lons, spec)
            fetchedAt = now
            cacheDir.mkdirs()
            // Expired grids only: the other zoom's fresh file is still wanted.
            cacheDir.listFiles { f -> f.name.startsWith(CACHE_PREFIX) && now - f.lastModified() >= MAX_AGE_MS }?.forEach { it.delete() }
            file.writeText(text)
            file.setLastModified(fetchedAt)
            Log.i(TAG, "RAIN_GRID outcome=fetched points=${lats.size * lons.size} spanDeg=${spec.halfSpanLatDeg} bytes=${text.length}")
        }
        parse(text, lats, lons, fetchedAt)
    }.onFailure {
        // ktor puts the whole request URL (5 KB of coordinates) in timeout messages.
        Log.w(TAG, "RAIN_GRID outcome=failed error=${it::class.simpleName}: ${it.message?.substringBefore(" [url=")?.take(160)}")
    }

    private suspend fun fetch(lats: DoubleArray, lons: DoubleArray, spec: RainGridSpec): String {
        val pointLats = StringBuilder()
        val pointLons = StringBuilder()
        for (r in lats.indices) for (c in lons.indices) {
            if (pointLats.isNotEmpty()) {
                pointLats.append(',')
                pointLons.append(',')
            }
            pointLats.append(fmt(lats[r]))
            pointLons.append(fmt(lons[c]))
        }
        val response = httpClient.get(URL) {
            parameter("latitude", pointLats.toString())
            parameter("longitude", pointLons.toString())
            parameter("hourly", "precipitation,precipitation_probability")
            parameter("forecast_hours", spec.forecastHours)
            parameter("timeformat", "unixtime")
            parameter("timezone", "GMT")
            // A cold grid over new points takes Open-Meteo 10–20 s (measured 2026-10-20) — past the
            // app client's timeout. And no automatic retry: each attempt costs every point again
            // against the free quota; the viewer offers a manual Retry instead.
            if (httpClient.pluginOrNull(HttpTimeout) != null) {
                timeout {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MS
                }
            }
            retry { noRetry() }
        }
        val status = response.status.value
        if (status !in 200..299) throw RainLoopHttpException(status, "rain grid")
        return response.bodyAsText()
    }

    companion object {
        const val URL = "https://api.open-meteo.com/v1/forecast"

        /** Named in the viewer's error text. */
        val HOST: String = RainLoopLoadError.hostOf(URL)
        const val MAX_AGE_MS = 60L * 60 * 1000
        const val REQUEST_TIMEOUT_MS = 45_000L
        private const val CACHE_PREFIX = "rain_grid_"

        /** Geometry is part of the key: a ±1.5° grid has as many points as a ±4° one and would parse. */
        internal fun cacheName(lat: Double, lon: Double, spec: RainGridSpec) =
            CACHE_PREFIX + String.format(Locale.US, "%.4f_%.4f_%d_%.2f_%d", lat, lon, spec.size, spec.halfSpanLatDeg, spec.forecastHours) + ".json"

        /** Coordinates as sent: 4 decimals (~11 m), the precision Open-Meteo echoes. */
        internal fun fmt(v: Double): String = String.format(Locale.US, "%.4f", v)

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses Open-Meteo's multi-location reply: a JSON array in request order (row-major, south
         * → north, west → east). A single location comes back as a bare object. Nulls read as 0.
         */
        fun parse(text: String, lats: DoubleArray, lons: DoubleArray, fetchedAtMs: Long): RainGrid {
            val root = json.parseToJsonElement(text)
            val points: List<JsonObject> = if (root is JsonArray) root.map { it.jsonObject } else listOf(root.jsonObject)
            val n = lats.size * lons.size
            require(points.size == n) { "expected $n points, got ${points.size}" }
            val firstHourly = points[0]["hourly"]!!.jsonObject
            val times = firstHourly["time"]!!.jsonArray.map { (it.jsonPrimitive.longOrNull ?: 0L) * 1000 }.toLongArray()
            val hours = times.size
            val mm = Array(hours) { FloatArray(n) }
            val chance = Array(hours) { FloatArray(n) }
            points.forEachIndexed { i, p ->
                val hourly = p["hourly"]!!.jsonObject
                val pr = hourly["precipitation"]?.jsonArray
                val pp = hourly["precipitation_probability"]?.jsonArray
                for (h in 0 until hours) {
                    mm[h][i] = pr?.getOrNull(h)?.jsonPrimitive?.floatOrNull ?: 0f
                    chance[h][i] = pp?.getOrNull(h)?.jsonPrimitive?.floatOrNull ?: 0f
                }
            }
            return RainGrid(lats, lons, times, mm, chance, fetchedAtMs)
        }
    }
}
