package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.di.RepositoryEntryPoint
import com.weatherwidget.shared.nowcast.NowcastFetcher
import com.weatherwidget.shared.nowcast.NowcastRefreshPolicy
import com.weatherwidget.shared.nowcast.NowcastStore
import com.weatherwidget.shared.nowcast.NowcastSummary
import com.weatherwidget.shared.util.Log
import dagger.hilt.android.EntryPointAccessors
import io.ktor.client.HttpClient
import java.io.File

/** The app's one nowcast store: a cache file per site, beside the rain map's. */
object AndroidNowcast {
    @Volatile private var store: NowcastStore? = null

    fun store(context: Context): NowcastStore =
        store ?: synchronized(this) {
            store ?: NowcastStore(File(context.applicationContext.cacheDir, "nowcast")).also { store = it }
        }
}

/**
 * Open-Meteo's 15-minute nowcast, refreshed from INSIDE the current-temp branch's already-gated
 * fetch block (like [MetarObservationRefresher]): it inherits that run's charging, screen and
 * battery rules and adds no wakeup. [NowcastRefreshPolicy] then asks for Open-Meteo displayed on a
 * widget, native coverage, a series ≥ 15 min old and rain plausible in the next 3 h.
 * `plans/261021-open-meteo-15-min-nowcast.md`.
 */
internal class NowcastRefresher(
    private val context: Context,
    private val widgetStateManager: WidgetStateManager,
    private val appLogDao: AppLogDao,
    private val store: NowcastStore = AndroidNowcast.store(context),
    private val httpClient: () -> HttpClient = {
        EntryPointAccessors.fromApplication(context.applicationContext, RepositoryEntryPoint::class.java).httpClient()
    },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** True when a nowcast was fetched (the caller's repaint then shows it). */
    suspend fun refreshIfDue(lat: Double, lon: Double, reason: String): Boolean {
        val now = nowMs()
        val openMeteoDisplayed = WeatherSource.OPEN_METEO.id in widgetStateManager.getActiveDisplaySourceIds()
        // Raw proximity-box rows on purpose (allow-listed): only their MAX chance is read, as a
        // "rain plausible?" gate. A frozen fragment can at worst cost one extra ~1 KB request; it
        // never reaches anything drawn.
        val hourly = if (openMeteoDisplayed) {
            WeatherDatabase.getDatabase(context).hourlyForecastDao()
                .getHourlyForecasts(now - HOUR_MS, now + NowcastRefreshPolicy.PLAUSIBLE_WINDOW_MS, lat, lon)
                .map { it.toHourlyForecast() }
        } else {
            emptyList()
        }
        val maxPop = NowcastRefreshPolicy.maxPopNext3h(hourly, now)
        val skip = NowcastRefreshPolicy.skipReason(
            openMeteoDisplayed = openMeteoDisplayed,
            nativeCoverage = store.loadCoverage(lat, lon, now),
            cached = store.loadSeries(lat, lon, now),
            maxPopNext3h = maxPop,
            nowMs = now,
        )
        if (skip != null) {
            Log.d(TAG, "NOWCAST_SKIP reason=${skip.logName} trigger=$reason maxPop=$maxPop")
            return false
        }
        val outcome = NowcastFetcher(httpClient(), store, nowMs).refresh(lat, lon)
        appLogDao.log(
            "NOWCAST_FETCH",
            "trigger=$reason maxPop=$maxPop ${outcome.logLine}",
            if (outcome is NowcastFetcher.Outcome.Failed) "WARN" else "INFO",
        )
        if (outcome !is NowcastFetcher.Outcome.Fetched) return false
        val summary = store.loadSeries(lat, lon, now)?.let { NowcastSummary.summarize(it, now) }
        appLogDao.log("NOWCAST_SUMMARY", "text=${NowcastSummary.describe(summary, use24HourClock = true) ?: "dry"}")
        return true
    }

    private companion object {
        const val TAG = "NowcastRefresher"
        const val HOUR_MS = 60L * 60 * 1000
    }
}
