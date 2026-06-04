package com.weatherwidget.data.local.desktop

import java.time.Instant
import java.time.ZoneId

/**
 * Derives actual daily highs/lows from stored observations — the "actuals" half of forecast
 * accuracy tracking. This is the desktop's simplified analogue of the Android
 * `ObservationResolver.computeDailyExtremes` / `recomputeDailyExtremesForDay` pipeline: single
 * station, plain per-day max/min (no inverse-distance blending).
 *
 * Pure function over a list of observations so it is unit-testable without a database or network.
 */
object DailyExtremesComputer {
    const val MS_IN_A_DAY = 86_400_000L

    // Clock-based day window matching the Android split: day = 08:00–20:00 local, night otherwise.
    private const val DAY_START_HOUR = 8
    private const val DAY_END_HOUR = 20

    /**
     * Groups [observations] by local calendar day (in [zone]) and source, returning one extreme row
     * per (day, source, location). [updatedAt] stamps every produced row (used for retention).
     */
    fun compute(
        observations: List<DesktopObservationEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
        updatedAt: Long = System.currentTimeMillis(),
    ): List<DesktopDailyExtremeEntity> {
        if (observations.isEmpty()) return emptyList()

        return observations
            .filter { it.stationId != "NWS_BLEND" }
            .groupBy { obs ->
                val day = Instant.ofEpochMilli(obs.timestamp).atZone(zone).toLocalDate()
                ExtremeKey(day.toEpochDay(), obs.api, obs.locationLat, obs.locationLon)
            }
            .map { (key, dayObs) ->
                val high = dayObs.maxByOrNull { it.temperature }!!
                val low = dayObs.minByOrNull { it.temperature }!!
                // Condition of the warmest reading is a reasonable proxy for the day's headline weather.
                val condition = high.condition

                val precipTotal = dayObs.mapNotNull { it.precipAmountMm }.takeIf { it.isNotEmpty() }?.sum()
                val (dayMm, nightMm) = splitPrecip(dayObs, zone)

                DesktopDailyExtremeEntity(
                    date = key.epochDay * MS_IN_A_DAY,
                    source = key.api,
                    locationLat = key.lat,
                    locationLon = key.lon,
                    highTemp = high.temperature,
                    lowTemp = low.temperature,
                    condition = condition,
                    updatedAt = updatedAt,
                    precipAmountMm = precipTotal,
                    precipDayMm = dayMm,
                    precipNightMm = nightMm,
                )
            }
    }

    private fun splitPrecip(dayObs: List<DesktopObservationEntity>, zone: ZoneId): Pair<Float?, Float?> {
        var day = 0f
        var night = 0f
        var sawDay = false
        var sawNight = false
        for (obs in dayObs) {
            val mm = obs.precipAmountMm ?: continue
            val hour = Instant.ofEpochMilli(obs.timestamp).atZone(zone).hour
            if (hour in DAY_START_HOUR until DAY_END_HOUR) {
                day += mm; sawDay = true
            } else {
                night += mm; sawNight = true
            }
        }
        return Pair(if (sawDay) day else null, if (sawNight) night else null)
    }

    private data class ExtremeKey(val epochDay: Long, val api: String, val lat: Double, val lon: Double)
}
