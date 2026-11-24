package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.AndroidRadarNowStore
import com.weatherwidget.shared.observations.RadarNowSample
import com.weatherwidget.shared.observations.RainNow

/**
 * Android's inputs to the shared [RainNow] rule for the header's rain-now dots
 * (plans/261115-header-rain-now-dots.md): the site's station and Tomorrow.io rows from the last
 * [RainNow.STATION_MAX_AGE_MS] and the radar sample the worker stored. The rule, its windows and its
 * `RAIN_NOW` log are shared with desktop.
 */
object AndroidRainNow {
    /** Station networks plus Tomorrow.io's 5-minute history; [RainNow] tells them apart. */
    private val APIS = setOf(
        WeatherSource.NWS.id,
        WeatherSource.METAR.id,
        WeatherSource.TOMORROW_IO.id,
    )

    suspend fun level(
        context: Context,
        lat: Double,
        lon: Double,
        displaySource: WeatherSource,
        where: String,
        nowMs: Long = System.currentTimeMillis(),
    ): RainNow.Level? {
        val rows = WeatherDatabase.getDatabase(context).observationDao().getObservationsInRange(
            nowMs - RainNow.STATION_MAX_AGE_MS,
            nowMs + RainNow.FUTURE_SKEW_MS,
            lat,
            lon,
            apis = APIS,
        )
        val verdict = RainNow.resolve(
            readings = rows.map { it.toReading() },
            radar = RadarNowSample.atSite(AndroidRadarNowStore(context).read(), lat, lon),
            displaySourceId = displaySource.id,
            nowMs = nowMs,
        )
        RainNow.logTransition(verdict, where, nowMs)
        return verdict.level
    }
}
