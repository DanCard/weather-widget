package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.local.LocationMatch

/**
 * The `daily_history` key, one rule for both platforms: a row is filed under the
 * [LocationMatch.quantize]d site, so GPS jitter cannot split one place's day into fragments.
 * Neither platform rounded before 2026-10-25 — desktop wrote the raw config fix, Android the raw
 * GPS fix of the moment (the Pixel had one site's days on 13 keys) — while observations were
 * already rounded (plans/261025-shared-daily-blend-recompute.md, phase 0).
 *
 * Both write boundaries ([quantized]) and both one-time migrations ([mergeAll]) go through here.
 */
object DailyHistoryKey {
    fun quantized(row: DailyHistory): DailyHistory {
        val lat = LocationMatch.quantize(row.locationLat)
        val lon = LocationMatch.quantize(row.locationLon)
        return if (lat == row.locationLat && lon == row.locationLon) row else row.copy(locationLat = lat, locationLon = lon)
    }

    /** True when [row] is already on the rounded key. */
    fun isQuantized(row: DailyHistory): Boolean =
        LocationMatch.quantize(row.locationLat) == row.locationLat && LocationMatch.quantize(row.locationLon) == row.locationLon

    /**
     * Every row on its rounded key, fragments of one (date, source, site) merged into one ([merge]).
     * Rows already alone on a rounded key come back unchanged.
     */
    fun mergeAll(rows: List<DailyHistory>): List<DailyHistory> =
        rows.groupBy { val q = quantized(it); listOf(q.date, q.source, q.locationLat, q.locationLon) }
            .values
            .map { group -> if (group.size == 1) quantized(group.single()) else merge(group) }

    /**
     * One row from a day's fragments (user, 2026-10-25: fill gaps): the most recently updated fragment
     * is the base, and each value it lacks comes from the others, freshest first. Values that only
     * mean something together fill as a unit — a station's api actual with its station and provenance,
     * a blend with its times and precip — so no row pairs one fragment's station with another's
     * temperatures. Nothing is invented: every value was stored on some fragment.
     */
    fun merge(fragments: List<DailyHistory>): DailyHistory {
        require(fragments.isNotEmpty())
        // Ties on updatedAt: the fragment already on the rounded key, then coordinates — never row order.
        val ordered = fragments.sortedWith(
            compareByDescending<DailyHistory> { it.updatedAt }
                .thenByDescending { isQuantized(it) }
                .thenBy { it.locationLat }
                .thenBy { it.locationLon },
        )
        var merged = ordered.first()
        for (other in ordered.drop(1)) {
            if (merged.computedHighTemp == null && merged.computedLowTemp == null &&
                (other.computedHighTemp != null || other.computedLowTemp != null)
            ) {
                merged = merged.copy(
                    computedHighTemp = other.computedHighTemp,
                    computedLowTemp = other.computedLowTemp,
                    computedHighAt = other.computedHighAt,
                    computedLowAt = other.computedLowAt,
                    condition = other.condition,
                    precipAmountMm = other.precipAmountMm,
                    precipDayMm = other.precipDayMm,
                    precipNightMm = other.precipNightMm,
                )
            }
            if (merged.apiHighTemp == null && merged.apiLowTemp == null &&
                (other.apiHighTemp != null || other.apiLowTemp != null)
            ) {
                merged = merged.copy(
                    apiHighTemp = other.apiHighTemp,
                    apiLowTemp = other.apiLowTemp,
                    apiStationId = other.apiStationId,
                    apiStationDistanceKm = other.apiStationDistanceKm,
                    actualsSource = other.actualsSource,
                )
            }
            merged = merged.copy(
                forecastDayPrecipChance = merged.forecastDayPrecipChance ?: other.forecastDayPrecipChance,
                forecastNightPrecipChance = merged.forecastNightPrecipChance ?: other.forecastNightPrecipChance,
                forecastHighTemp = merged.forecastHighTemp ?: other.forecastHighTemp,
                forecastLowTemp = merged.forecastLowTemp ?: other.forecastLowTemp,
                forecastPrecipAmountMm = merged.forecastPrecipAmountMm ?: other.forecastPrecipAmountMm,
                noonCloudPercent = merged.noonCloudPercent ?: other.noonCloudPercent,
                priorForecastHighTemp = merged.priorForecastHighTemp ?: other.priorForecastHighTemp,
                priorForecastLowTemp = merged.priorForecastLowTemp ?: other.priorForecastLowTemp,
            )
        }
        return quantized(merged)
    }
}
