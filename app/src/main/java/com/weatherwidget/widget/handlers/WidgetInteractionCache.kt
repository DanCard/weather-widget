package com.weatherwidget.widget.handlers

import android.os.SystemClock
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.widget.DailyActualsBySource
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * background/worker paint uses a different path (WidgetRenderer) and is untouched, so a stale entry
 * can never leak into a scheduled/fetch-driven repaint.
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

    /**
     * [historyDays]/[forecastDays] are part of the key because the load window is no longer a
     * constant — it is sized to the widest installed widget's rendered range
     * ([DailyLoadWindowResolver]). Widgets in one tap burst resolve the same window and so still
     * share a single load, but a nav tap that widens the window can never be served a narrower
     * entry left over from before it.
     */
    data class Key(
        val latQ: Long,
        val lonQ: Long,
        val epochDay: Long,
        val historyDays: Long,
        val forecastDays: Long,
    ) {
        companion object {
            fun of(
                lat: Double,
                lon: Double,
                epochDay: Long,
                historyDays: Long,
                forecastDays: Long,
            ): Key =
                Key(
                    latQ = Math.round(lat * COORD_QUANTIZE),
                    lonQ = Math.round(lon * COORD_QUANTIZE),
                    epochDay = epochDay,
                    historyDays = historyDays,
                    forecastDays = forecastDays,
                )
        }
    }

    data class Data(
        /** Raw forecast rows (before ClimateGapFiller) for the daily lookback+forecast range. */
        val weatherListRaw: List<ForecastEntity>,
        val dailyActuals: DailyActualsBySource,
    )

    private class Entry(val data: Data, val storedAtElapsedMs: Long)

    // Neither map is actively evicted: expired entries are dropped lazily on get(), and
    // loadMutexes only grows with distinct keys. Bounded in practice — keys are 3dp-quantized
    // coordinates × epoch-day × load window, and the app has few locations — so correctness
    // never depends on explicit invalidation.
    private val entries = ConcurrentHashMap<Key, Entry>()
    private val loadMutexes = ConcurrentHashMap<Key, Mutex>()

    /** Returns cached [Data] for [key] if present and within [TTL_MS] of [nowElapsedMs], else null. */
    fun get(key: Key, nowElapsedMs: Long): Data? {
        val e = entries[key] ?: return null
        if (nowElapsedMs - e.storedAtElapsedMs > TTL_MS) {
            entries.remove(key, e)
            return null
        }
        return e.data
    }

    fun put(key: Key, data: Data, nowElapsedMs: Long) {
        entries[key] = Entry(data, nowElapsedMs)
    }

    /**
     * Returns a fresh cached value or performs exactly one load for [key]. Concurrent callers for
     * the same key await the same critical section; unrelated locations use unrelated mutexes.
     *
     * The completion clock is read after [loader] returns, so a slow database aggregation does not
     * consume the short cache lifetime while it is still running.
     */
    suspend fun getOrLoad(
        key: Key,
        nowElapsedMs: () -> Long = ::nowMs,
        loader: suspend () -> Data,
    ): Data {
        get(key, nowElapsedMs())?.let { return it }

        val mutex = loadMutexes.computeIfAbsent(key) { Mutex() }
        return mutex.withLock {
            get(key, nowElapsedMs())?.let { return@withLock it }
            loader().also { loaded ->
                put(key, loaded, nowElapsedMs())
            }
        }
    }

    /** Drops any cached entry. Not required for correctness (TTL bounds staleness); used by tests. */
    fun clear() {
        entries.clear()
        loadMutexes.clear()
    }

    fun nowMs(): Long = SystemClock.elapsedRealtime()
}
