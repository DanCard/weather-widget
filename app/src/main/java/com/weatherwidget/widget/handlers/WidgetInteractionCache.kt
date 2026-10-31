package com.weatherwidget.widget.handlers

import android.os.SystemClock
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.shared.util.VersionedTtlCache
import com.weatherwidget.widget.DailyActualsBySource
import java.time.LocalDate

/**
 * Short-lived cache for the two heaviest, location-keyed loads on the widget interaction path:
 * the raw daily forecast list and [DailyActualsBySource] (the latter reads ~1.6k observations and
 * builds ~1k candidate points — ~150-350ms each).
 *
 * Both are keyed on `(lat, lon, today)`, NOT on the widget, so a burst of taps across several widget
 * instances — or repeated taps on one widget faster than a repaint completes — share a single load
 * instead of re-querying per tap. On a 5-widget device this is what turned a ~500ms paint into
 * a ~1.4s handler under contention (see TEMP_ACTUALS_PERF / *_SLOW app_logs).
 *
 * Scope is deliberately the interaction path only ([DailyInteractionRenderer]). The
 * background/worker paint uses a different path (WidgetRenderer), which also [clear]s it.
 *
 * An entry lives [TTL_MS] (60 s) **or until a stored-data write**, whichever is first: the shared
 * write runners bump [com.weatherwidget.shared.util.StoredDataVersion], and [VersionedTtlCache] treats
 * any bump since an entry's load as a miss. The TTL alone let the UI-only repaint after a History
 * refresh draw the pre-fetch rows (Sat rain 12% for ~60 s after 21% was stored, 2026-10-29;
 * plans/261029-stored-data-version-invalidates-interaction-cache.md).
 */
internal object WidgetInteractionCache {
    /** Cache lifetime. Reused across user tap bursts and screen-on UI updates; a stored write ends it early. */
    const val TTL_MS = 60_000L

    /** Lat/lon quantization for the key — matches the 3dp coordinate quantization used elsewhere. */
    private const val COORD_QUANTIZE = 1_000.0

    /**
     * [historyDays]/[forecastDays] are part of the key because the load window is no longer a
     * constant — it is sized to the widest installed widget's rendered range
     * ([DailyLoadWindowResolver]). Widgets in one tap burst resolve the same window and so still
     * share a single load, but a nav tap that widens the window can never be served a narrower
     * entry left over from before it.
     *
     * [hourlySourceIds] is in the key because the hourly rows are loaded for exactly those sources:
     * without it, a source switch inside the TTL reads the previous source's rows, finds none for the
     * new one, and the header falls back to today's daily rain chance.
     */
    data class Key(
        val latQ: Long,
        val lonQ: Long,
        val epochDay: Long,
        val historyDays: Long,
        val forecastDays: Long,
        val hourlySourceIds: Set<String> = emptySet(),
    ) {
        companion object {
            fun of(
                lat: Double,
                lon: Double,
                epochDay: Long,
                historyDays: Long,
                forecastDays: Long,
                hourlySourceIds: Collection<String> = emptyList(),
            ): Key =
                Key(
                    latQ = Math.round(lat * COORD_QUANTIZE),
                    lonQ = Math.round(lon * COORD_QUANTIZE),
                    epochDay = epochDay,
                    historyDays = historyDays,
                    forecastDays = forecastDays,
                    hourlySourceIds = hourlySourceIds.toSet(),
                )
        }
    }

    data class Data(
        /** Raw forecast rows (before ClimateGapFiller) for the daily lookback+forecast range. */
        val weatherListRaw: List<ForecastEntity>,
        val dailyActuals: DailyActualsBySource,
        val forecastSnapshots: Map<LocalDate, List<ForecastEntity>>? = null,
        val hourlyForecasts: List<HourlyForecastEntity>? = null,
        val currentTemps: List<ObservationEntity>? = null,
    )

    private val cache = VersionedTtlCache<Key, Data>(TTL_MS)

    /** Cached [Data] for [key] if within [TTL_MS] of [nowElapsedMs] and no write since its load, else null. */
    fun get(key: Key, nowElapsedMs: Long): Data? = cache.get(key, nowElapsedMs)

    fun put(key: Key, data: Data, nowElapsedMs: Long) = cache.put(key, data, nowElapsedMs)

    /** A fresh cached value or exactly one load for [key]; see [VersionedTtlCache.getOrLoad]. */
    suspend fun getOrLoad(
        key: Key,
        nowElapsedMs: () -> Long = ::nowMs,
        loader: suspend () -> Data,
    ): Data = cache.getOrLoad(key, nowElapsedMs, loader)

    /** Drops every entry: a non-UI-only paint and a user-initiated refresh load fresh. */
    fun clear() = cache.clear()

    fun nowMs(): Long = SystemClock.elapsedRealtime()
}
