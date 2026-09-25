package com.weatherwidget.shared.actuals

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.remote.AviationWeatherStationFilter
import java.time.LocalDate
import kotlin.math.abs

/**
 * Fills yesterday, when the current site never measured it, with the same source's measured history from
 * another site — typically where the user was before a move. Display-only: nothing here is written
 * back, and accuracy/Forecast History reads stay strictly location-scoped.
 *
 * Why: every history read is scoped to the [LocationMatch] box, so after a move the past columns
 * showed forecast-only bars even though the same source had measured those days a few hundred km
 * back along the trip. The user wants "how much warmer or colder than yesterday", and said the exact
 * place matters less (bug report 2026-09-24). A borrowed day is tagged
 * ([DailyHistory.actualsBorrowedFromKm]) so renderers draw its actual bar dashed.
 *
 * Rules:
 *  - Only YESTERDAY (`today - 1`) is ever borrowed (user decision 2026-09-25: at most one day of
 *    another site's history). Older past days stay local-only; today is live and always local.
 *  - A measured local row is never replaced.
 *  - Donors must be measured ([DailyHistory.hasActuals]) and outside the current [LocationMatch] box.
 *  - The nearest donor wins, whatever the distance (user decision: no cap); ties go to the newest
 *    `updatedAt`.
 *  - A local forecast-only row keeps its own forecast overlay, cloud and rain fields; only
 *    `computedHighTemp`/`computedLowTemp` are taken from the donor.
 */
object PreviousSiteHistory {

    fun fill(
        local: Map<String, Map<LocalDate, DailyHistory>>,
        candidates: List<DailyHistory>,
        lat: Double,
        lon: Double,
        today: LocalDate,
    ): Map<String, Map<LocalDate, DailyHistory>> {
        val donorsBySourceDate = candidates
            .asSequence()
            .filter { it.hasActuals && !isLocal(it, lat, lon) && it.toLocalDate() == today.minusDays(1) }
            .groupBy { it.source to it.toLocalDate() }
        if (donorsBySourceDate.isEmpty()) return local

        val result = local.mapValues { it.value.toMutableMap() }.toMutableMap()
        for ((key, donors) in donorsBySourceDate) {
            val (source, date) = key
            val byDate = result.getOrPut(source) { mutableMapOf() }
            val existing = byDate[date]
            if (existing?.hasActuals == true) continue
            val donor = donors
                .map { it to AviationWeatherStationFilter.distanceKm(lat, lon, it.locationLat, it.locationLon) }
                .sortedWith(compareBy<Pair<DailyHistory, Double>>({ it.second }, { -it.first.updatedAt }))
                .first()
            val (row, km) = donor
            byDate[date] = existing?.copy(
                computedHighTemp = row.computedHighTemp,
                computedLowTemp = row.computedLowTemp,
                actualsBorrowedFromKm = km,
            ) ?: row.copy(actualsBorrowedFromKm = km, borrowedWithoutLocalRow = true)
        }
        return result
    }

    private fun isLocal(row: DailyHistory, lat: Double, lon: Double): Boolean =
        abs(row.locationLat - lat) <= LocationMatch.TOLERANCE_DEG &&
            abs(row.locationLon - lon) <= LocationMatch.TOLERANCE_DEG
}
