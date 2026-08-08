package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.observations.ObservationSourceMatcher
import java.time.Instant
import java.time.ZoneId

/**
 * Resolves one calendar day's high/low from a **single official station's raw readings**.
 *
 * This is deliberately not the IDW blend ([ActualsAggregator.aggregate] /
 * [ActualTemperatureSeriesBuilder]). The blend interpolates to the user's coordinates and carries
 * stations forward across gaps using the *forecast's* slope
 * (`ActualTemperatureSeriesBuilder.extrapolateForward`), which makes it unsuitable as an accuracy
 * baseline: scoring a forecast against a partly forecast-derived actual understates the error.
 * A single station's raw min/max has no path back to the forecast.
 *
 * The two live side by side in `daily_history` — `computedHighTemp`/`computedLowTemp` hold the
 * blend, `apiHighTemp`/`apiLowTemp` hold this — so switching which one drives the statistics screen
 * costs nothing and rewrites no history.
 */
object StationDailyExtremes {

    /**
     * A station must have reported inside these local-hour windows to be trusted for the day's
     * extremes. Without the guard a station that lapsed after breakfast reports its late-morning
     * peak as the day's high: at the reference location KPAO logs 13-15 readings/day against KNUQ's
     * 47-79, so the sparse station is a live hazard, not a hypothetical one.
     */
    const val HIGH_WINDOW_START_HOUR = 12
    const val HIGH_WINDOW_END_HOUR = 18 // exclusive
    const val LOW_WINDOW_START_HOUR = 0
    const val LOW_WINDOW_END_HOUR = 7 // exclusive

    private const val OFFICIAL_STATION_TYPE = "OFFICIAL"

    data class StationDailyExtreme(
        val stationId: String,
        val stationName: String,
        val distanceKm: Float,
        val high: Float,
        val low: Float,
        val readingCount: Int,
    )

    /**
     * @param observations any pool of readings; scoped internally to [sourceId] and to
     *   `[dayStartMs, dayEndMs)`, so callers may pass a wider context window without pre-filtering.
     * @param dayEndMs exclusive.
     * @return the nearest OFFICIAL station that satisfies the coverage guard, or null when none
     *   does. Null means "this day has no trustworthy station actual" and callers must exclude the
     *   day rather than substitute anything else.
     */
    fun resolve(
        observations: List<ObservationReading>,
        sourceId: String,
        dayStartMs: Long,
        dayEndMs: Long,
        zone: ZoneId,
    ): StationDailyExtreme? {
        val candidates = observations.filter { reading ->
            reading.api == sourceId &&
                reading.timestamp >= dayStartMs &&
                reading.timestamp < dayEndMs &&
                reading.stationType == OFFICIAL_STATION_TYPE &&
                !reading.qcFailed &&
                reading.stationId != "NWS_BLEND" &&
                !ObservationSourceMatcher.isSyntheticBackfillStation(reading.stationId, sourceId)
        }
        if (candidates.isEmpty()) return null

        return candidates
            .groupBy { it.stationId }
            .entries
            // Nearest first. A station's rows can disagree on distanceKm by metres of GPS jitter,
            // so rank on its minimum rather than an arbitrary row's value.
            .sortedBy { (_, readings) -> readings.minOf { it.distanceKm } }
            .firstNotNullOfOrNull { (stationId, readings) ->
                if (!hasRequiredCoverage(readings, zone)) return@firstNotNullOfOrNull null
                StationDailyExtreme(
                    stationId = stationId,
                    stationName = readings.first().stationName,
                    distanceKm = readings.minOf { it.distanceKm },
                    high = readings.maxOf { it.temperature },
                    low = readings.minOf { it.temperature },
                    readingCount = readings.size,
                )
            }
    }

    /** True when [readings] span both the afternoon (for the high) and the pre-dawn hours (for the low). */
    private fun hasRequiredCoverage(readings: List<ObservationReading>, zone: ZoneId): Boolean {
        var coversHighWindow = false
        var coversLowWindow = false
        for (reading in readings) {
            val hour = Instant.ofEpochMilli(reading.timestamp).atZone(zone).hour
            if (hour in HIGH_WINDOW_START_HOUR until HIGH_WINDOW_END_HOUR) coversHighWindow = true
            if (hour in LOW_WINDOW_START_HOUR until LOW_WINDOW_END_HOUR) coversLowWindow = true
            if (coversHighWindow && coversLowWindow) return true
        }
        return false
    }
}
