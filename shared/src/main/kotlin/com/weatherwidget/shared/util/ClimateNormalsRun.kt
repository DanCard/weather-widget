package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import java.time.MonthDay
import kotlin.coroutines.cancellation.CancellationException

/**
 * The site's climate normals (the future-day fallback), fetched once and cached, the same way on
 * both platforms: cache first, then — only when the user has Open-Meteo enabled, whose archive they
 * come from — a fetch of the rolling window, reduced by [ClimateNormals]. Was Android's
 * `ClimateNormalsRepository` and desktop's `ensureClimateNormals`; desktop fetched with Open-Meteo
 * disabled and swallowed cancellation (audit plans/261023-duplicate-logic-audit-different-names.md §2).
 */
object ClimateNormalsRun {
    interface Host {
        /** Cached monthly (high, low) for [locationKey]; empty maps when none. */
        suspend fun cached(locationKey: String): Pair<Map<Int, Float>, Map<Int, Float>>

        /** The user has Open-Meteo among their sources. */
        val openMeteoEnabled: Boolean

        /** Open-Meteo archive daily highs / lows over [start]..[end] (ISO dates). */
        suspend fun fetchDailyTemps(start: String, end: String): List<com.weatherwidget.data.model.DailyForecast>

        /** Stores this site's normals and drops every other location's (retention: current location only). */
        suspend fun store(locationKey: String, monthlyHigh: Map<Int, Float>, monthlyLow: Map<Int, Float>)

        suspend fun log(tag: String, message: String, level: String)
    }

    data class Result(val byMonthDay: Map<MonthDay, Pair<Float, Float>>, val fetched: Boolean)

    /** Throws on a failed fetch (callers that are best-effort use [warm]). */
    suspend fun run(host: Host, latitude: Double, longitude: Double): Result {
        val key = ClimateNormals.locationKey(latitude, longitude)
        val (cachedHigh, cachedLow) = host.cached(key)
        if (cachedHigh.isNotEmpty() && cachedLow.isNotEmpty()) {
            return Result(ClimateNormals.expandMonthlyToDaily(cachedHigh, cachedLow), fetched = false)
        }
        if (!host.openMeteoEnabled) {
            host.log("CLIMATE_SKIP_DISABLED", "source=${WeatherSource.OPEN_METEO.id}", "DEBUG")
            return Result(emptyMap(), fetched = false)
        }
        val (start, end) = ClimateNormals.rollingWindow()
        val daily = host.fetchDailyTemps(start, end)
        val (monthlyHigh, monthlyLow) = ClimateNormals.monthlyMeans(daily)
        if (monthlyHigh.isEmpty() || monthlyLow.isEmpty()) {
            host.log("CLIMATE_FETCH_EMPTY", "lat=$latitude lon=$longitude rows=${daily.size}", "WARN")
            return Result(emptyMap(), fetched = false)
        }
        host.store(key, monthlyHigh, monthlyLow)
        host.log("CLIMATE_CACHED", "key=$key months=${monthlyHigh.size}", "INFO")
        return Result(ClimateNormals.expandMonthlyToDaily(monthlyHigh, monthlyLow), fetched = true)
    }

    /** Best-effort: a failure is logged (`CLIMATE_WARM_FAIL`), never thrown; cancellation still propagates. */
    suspend fun warm(host: Host, latitude: Double, longitude: Double): Result =
        try {
            run(host, latitude, longitude)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.log("CLIMATE_WARM_FAIL", "lat=$latitude lon=$longitude error=${e::class.simpleName}:${e.message}", "WARN")
            Result(emptyMap(), fetched = false)
        }
}
