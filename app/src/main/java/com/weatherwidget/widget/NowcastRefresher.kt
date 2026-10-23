package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.di.RepositoryEntryPoint
import com.weatherwidget.shared.nowcast.NowcastFetcher
import com.weatherwidget.shared.nowcast.NowcastRefreshRun
import com.weatherwidget.shared.nowcast.NowcastStore
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
    /** True when a nowcast was fetched (the caller's repaint then shows it). The rule is `:shared` [NowcastRefreshRun]. */
    suspend fun refreshIfDue(lat: Double, lon: Double, reason: String): Boolean {
        val outcome = NowcastRefreshRun.run(
            object : NowcastRefreshRun.Host {
                override val lat = lat
                override val lon = lon
                override val openMeteoDisplayed = WeatherSource.OPEN_METEO.id in widgetStateManager.getActiveDisplaySourceIds()
                override val store = this@NowcastRefresher.store

                // Raw proximity-box rows of every source on purpose (allow-listed): only their MAX
                // chance is read, as a "rain plausible?" gate. A frozen fragment can at worst cost one
                // extra ~1 KB request; it never reaches anything drawn.
                override suspend fun hourly(fromMs: Long, toMs: Long) =
                    WeatherDatabase.getDatabase(context).hourlyForecastDao()
                        .getHourlyForecasts(fromMs, toMs, lat, lon)
                        .map { it.toHourlyForecast() }

                override suspend fun fetch() = NowcastFetcher(httpClient(), store, nowMs).refresh(lat, lon)

                override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)
            },
            reason = reason,
            nowMs = nowMs(),
        )
        return outcome is NowcastFetcher.Outcome.Fetched
    }

}
