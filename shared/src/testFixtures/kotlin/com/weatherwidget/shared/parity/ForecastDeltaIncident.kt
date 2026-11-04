package com.weatherwidget.shared.parity

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.HourlyForecastStitcher
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import java.time.LocalDate
import java.time.ZoneId

/**
 * The real rows behind "+2.5 in the daily view, +3.9 on the temperature graph" (Open-Meteo,
 * 2026-10-30): the 10:24 fetch raised 11:00 by 2.3°. Since the pre-hour freeze was removed
 * (2026-11-04) the started 10:00 hour takes that fetch too, so the delta is against one run. Both platforms' integration tests run these rows through their real views and must print
 * the literal [DELTA_TEXT]. See plans/261030-one-forecast-delta-all-views.md.
 *
 * Dated [day] rather than 2026-10-30 because the hourly read ages rows out by the wall clock.
 */
class ForecastDeltaIncident(val day: LocalDate = LocalDate.now(), val zone: ZoneId = ZoneId.systemDefault()) {
    fun ms(hour: Int, minute: Int = 0): Long = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    val nowMs = ms(10, 48)
    val observedAt = ms(10, 35)

    private fun run(hour: Int, minute: Int, vararg temps: Pair<Int, Float>) = temps.map { (h, temp) ->
        HourlyForecast(
            dateTime = ms(h),
            temperature = temp,
            condition = "Clear",
            source = SOURCE.id,
            fetchedAt = ms(hour, minute),
            locationLat = LAT,
            locationLon = LON,
        )
    }

    val earlyRun = run(
        5, 3,
        5 to 54.5f, 6 to 53.5f, 7 to 53.3f, 8 to 54.2f, 9 to 56.3f, 10 to 58.2f,
        11 to 59.3f, 12 to 61.0f, 13 to 62.5f, 14 to 64.0f,
    )
    val lateRun = run(10, 24, 10 to 59.3f, 11 to 61.6f, 12 to 63.6f, 13 to 65.5f, 14 to 67.8f)

    /** What both platforms feed the resolver and the graph: each hour's newest fetch. */
    val stitched: List<HourlyForecast>
        get() = HourlyForecastStitcher.stitch(lateRun + earlyRun + lateRun, LAT, LON)

    val observations: List<ObservationReading>
        get() = (0..7).map { step ->
            val minutesBack = 35L * step
            ObservationReading(
                stationId = STATION,
                stationName = "Mountain View, Moffett Field",
                timestamp = observedAt - minutesBack * 60_000L,
                temperature = OBSERVED - step * 1.2f,
                condition = "Clear",
                locationLat = LAT,
                locationLon = LON,
                distanceKm = 3.8f,
                api = ActualsProviderResolver.providerIdFor(SOURCE),
                fetchedAt = nowMs,
            )
        }

    companion object {
        const val LAT = 37.417
        const val LON = -122.089
        const val STATION = "KNUQ"
        const val OBSERVED = 64.08f
        val SOURCE = WeatherSource.OPEN_METEO

        /** 64.08 − 60.64: the observation against the newest run at 10:35 (59.3 → 61.6). */
        const val DELTA = 3.44f
        const val DELTA_TEXT = "+3.4"

        /** 11:00 on the ghost line: 61.6 + 3.44. */
        const val GHOST_AT_11 = 65.04f
    }
}
