package com.weatherwidget.data.remote

import com.weatherwidget.data.model.WeatherSource

/**
 * How far each source's hourly forecast reaches — the one place sources differ for on-demand hourly
 * ([HourlyOnDemand]), so no rule there names a source
 * (`plans/261009-on-demand-hourly-shared-single-source-fetch.md`).
 *
 * @property routineHours what a normal fetch stores ([HourlyHorizons.ROUTINE_HOURS], at most).
 * @property maxHours the furthest its API serves, capped at [HourlyOnDemand.REACH_HOURS].
 * @property costsPerExtraDay whether reaching further costs requests (Google: a billed page per 24 h).
 */
data class HourlyHorizon(
    val routineHours: Int,
    val maxHours: Int,
    val costsPerExtraDay: Boolean,
) {
    /** True when a fetch stores less than the source can serve: hours past [routineHours] are on-demand only. */
    val hasOnDemandRange: Boolean get() = routineHours < maxHours
}

object HourlyHorizons {
    /**
     * Measured 2026-10-09 as the furthest stored hour after its fetch, on the desktop DB and the
     * Pixel: NWS 154 h, Tomorrow.io 97 h (its API documents 120 h), Open-Meteo ~375 h, Silurian 349 h.
     * OWM's free `/forecast` is 5 days, 3-hourly. WeatherAPI's free plan is 3 days.
     */
    /**
     * What every source stores routinely (2026-10-10): the daily view keeps its far days' noon cloud
     * and rain maxima on the forecast row ([com.weatherwidget.shared.util.DailyHourlySummaries]), so
     * hours past this are fetched on demand like Google's
     * (`performance/261010-daily-view-summaries-instead-of-far-hourly.md`).
     */
    const val ROUTINE_HOURS = 72

    fun of(sourceId: String): HourlyHorizon {
        val reach = HourlyOnDemand.REACH_HOURS
        fun full(apiHours: Int) = minOf(apiHours, reach).let {
            HourlyHorizon(minOf(ROUTINE_HOURS, it), it, costsPerExtraDay = false)
        }
        return when (sourceId) {
            WeatherSource.GOOGLE_WEATHER.id -> HourlyHorizon(GoogleWeatherApi.FORECAST_HOURS, reach, costsPerExtraDay = true)
            WeatherSource.NWS.id -> full(156)
            WeatherSource.TOMORROW_IO.id, WeatherSource.OPEN_WEATHER_MAP.id -> full(120)
            WeatherSource.WEATHER_API.id -> full(72)
            else -> full(reach) // Open-Meteo, Silurian and anything new: up to the app's reach
        }
    }
}
