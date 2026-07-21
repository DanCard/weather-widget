package com.weatherwidget.widget.handlers

import android.os.SystemClock
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.widget.DailyActualsBySource

/**
 * Short-lived cache for the two heaviest, location-keyed loads on the widget interaction path:
 * the raw daily forecast list and [DailyActualsBySource] (the latter reads ~1.6k observations and
 * builds ~1k candidate points — ~150-350ms each).
 *
 * Both are keyed on `(lat, lon, today)`, NOT on the widget, so a burst of taps across several widget
 * instances — or repeated taps on one widget faster than a repaint completes — can share a single
 * load instead of re-querying per tap. On a 5-widget device this is what turned a ~500ms paint into
 * a ~1.4s handler under contention (see TEMP_ACTUALS_PERF / *_SLOW app_logs).
 *
 * Scope is deliberately the interaction path only ([WidgetIntentRouter.refreshDailyView] and the
 * daily-nav bounds check). The background/worker paint uses a different path (WidgetRenderer) and is
 * untouched, so a stale entry can never leak into a scheduled/fetch-driven repaint.
 *
 * A [TTL_MS] of 2s is imperceptible staleness during active tapping and self-heals immediately after
 * — the next interaction >2s later reloads fresh, and any real data fetch repaints via the worker
 * path with live data regardless. Correctness never depends on invalidation; the TTL alone bounds it.
 */
internal object WidgetInteractionCache {
    /** Cache lifetime. Long enough to span a tap burst, short enough that staleness is invisible. */
    const val TTL_MS = 2_000L

    /** Lat/lon quantization for the key — matches the 3dp coordinate quantization used elsewhere. */
    private const val COORD_QUANTIZE = 1_000.0

    data class Key(val latQ: Long, val lonQ: Long, val epochDay: Long) {
        companion object {
            fun of(lat: Double, lon: Double, epochDay: Long): Key =
                Key(
                    latQ = Math.round(lat * COORD_QUANTIZE),
                    lonQ = Math.round(lon * COORD_QUANTIZE),
                    epochDay = epochDay,
                )
        }
    }

    data class Data(
        /** Raw forecast rows (before ClimateGapFiller) for the daily lookback+forecast range. */
        val weatherListRaw: List<ForecastEntity>,
        val dailyActuals: DailyActualsBySource,
    )

    private class Entry(val key: Key, val data: Data, val storedAtElapsedMs: Long)

    @Volatile private var entry: Entry? = null

    /** Returns cached [Data] for [key] if present and within [TTL_MS] of [nowElapsedMs], else null. */
    fun get(key: Key, nowElapsedMs: Long): Data? {
        val e = entry ?: return null
        if (e.key != key) return null
        if (nowElapsedMs - e.storedAtElapsedMs > TTL_MS) return null
        return e.data
    }

    fun put(key: Key, data: Data, nowElapsedMs: Long) {
        entry = Entry(key, data, nowElapsedMs)
    }

    /** Drops any cached entry. Not required for correctness (TTL bounds staleness); used by tests. */
    fun clear() {
        entry = null
    }

    fun nowMs(): Long = SystemClock.elapsedRealtime()
}
