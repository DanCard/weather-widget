package com.weatherwidget.shared.actuals

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.ObservationReading
import java.time.LocalDate
import java.time.ZoneId

/**
 * How a recomputed day's blend (high / low / precip from the stored observation pool) is written
 * onto `daily_history`, both platforms. Was Android's `DailyActualsStore.persistExtremes`; desktop's
 * copy compared against the first row anywhere in the proximity box and rewrote every row in full
 * (audit plans/261023-duplicate-logic-audit-different-names.md §2).
 *
 * The platform reads the day's rows in the box, applies [Plan.inserts] and each [Update] as a
 * conditional, field-limited write (expected `updatedAt`), and writes [Plan.logs].
 */
object DailyBlendWrite {
    data class Line(val tag: String, val message: String, val level: String)

    /** Write [merged]'s blend columns iff the stored row's `updatedAt` is still [expectedUpdatedAt]. */
    data class Update(val merged: DailyHistory, val expectedUpdatedAt: Long, val newUpdatedAt: Long)

    data class Plan(val inserts: List<DailyHistory>, val updates: List<Update>, val logs: List<Line>)

    /**
     * [newRows]: the day's recomputed blend per source. [existingInBox]: the day's stored rows from
     * the proximity-box read. [dayReadings]: the day's stored readings, every api ([StoredBlendGuard]
     * scopes them to the provider).
     */
    fun plan(
        date: LocalDate,
        today: LocalDate,
        newRows: List<DailyHistory>,
        existingInBox: List<DailyHistory>,
        latitude: Double,
        longitude: Double,
        dayReadings: List<ObservationReading>,
        zone: ZoneId,
    ): Plan {
        val inserts = mutableListOf<DailyHistory>()
        val updates = mutableListOf<Update>()
        val logs = mutableListOf<Line>()
        val bySource = existingInBox.groupBy { it.source }
        newRows.forEach { new ->
            // Collapse the box result to the anchor site before writing: the box (~7 mi) returns
            // fragments for sites visited hours ago, and an anchored blend written onto all of them
            // mixes sites. Samsung 2026-08-22: a GPS excursion's half-day low (57.03 → 66.52) was
            // written onto the home row 800 m away.
            val fragments = bySource[new.source].orEmpty()
                .filter { LocationMatch.sameSite(it.locationLat, it.locationLon, latitude, longitude) }
            if (fragments.isEmpty()) {
                inserts += new.copy(lastWriter = DailyHistoryWriter.BLEND_RECOMPUTE.storedValue)
                return@forEach
            }
            var changedAny = false
            fragments.forEach { existing ->
                // A past day keeps its stored high/low when it came from the NWS station pull, or when
                // this pool saw only part of the day. Today's row is never kept; CACHED_OBSERVATIONS
                // rows are not frozen (their blend comes from this same pool).
                val keep = StoredBlendGuard.keep(
                    date = date,
                    today = today,
                    source = new.source,
                    storedActualsSource = existing.actualsSource,
                    storedHigh = existing.computedHighTemp,
                    storedLow = existing.computedLowTemp,
                    readings = dayReadings,
                    zone = zone,
                )
                val freezeBlend = keep != StoredBlendGuard.Keep.NONE
                if (keep == StoredBlendGuard.Keep.UNCOVERED &&
                    (new.computedHighTemp != existing.computedHighTemp || new.computedLowTemp != existing.computedLowTemp)
                ) {
                    logs += Line(
                        "DAILY_HISTORY_KEPT_UNCOVERED",
                        "date=$date src=${new.source} at=${existing.locationLat},${existing.locationLon} " +
                            "kept=${existing.computedHighTemp}/${existing.computedLowTemp} " +
                            "partial=${new.computedHighTemp}/${new.computedLowTemp}",
                        "INFO",
                    )
                }
                // Built from `existing`, enumerating only the fields THIS writer owns: building from
                // `new` drops every column added later (actualsSource was lost that way the day it
                // was added, which also disabled the freeze guard that reads it).
                val merged = existing.copy(
                    computedHighTemp = if (freezeBlend) existing.computedHighTemp else new.computedHighTemp,
                    computedLowTemp = if (freezeBlend) existing.computedLowTemp else new.computedLowTemp,
                    // The times move with the values they describe (ForecastOverlaySettle). A frozen
                    // pull row written before v72/v25 has none; it adopts the recompute's.
                    computedHighAt = if (freezeBlend) existing.computedHighAt ?: new.computedHighAt else new.computedHighAt,
                    computedLowAt = if (freezeBlend) existing.computedLowAt ?: new.computedLowAt else new.computedLowAt,
                    condition = new.condition,
                    precipAmountMm = new.precipAmountMm,
                    precipDayMm = new.precipDayMm,
                    precipNightMm = new.precipNightMm,
                    lastWriter = DailyHistoryWriter.BLEND_RECOMPUTE.storedValue,
                )
                if (merged.copy(lastWriter = existing.lastWriter) != existing) {
                    changedAny = true
                    logs += Line(
                        "DAILY_HISTORY_OVERWRITE",
                        "date=$date src=${new.source} at=${existing.locationLat},${existing.locationLon} " +
                            "high=${existing.computedHighTemp}->${merged.computedHighTemp} low=${existing.computedLowTemp}->${merged.computedLowTemp} " +
                            "precip=${existing.precipAmountMm}->${new.precipAmountMm} " +
                            "apiHigh=${existing.apiHighTemp}->${merged.apiHighTemp} station=${merged.apiStationId}",
                        "DEBUG",
                    )
                    updates += Update(merged, expectedUpdatedAt = existing.updatedAt, newUpdatedAt = new.updatedAt)
                }
            }
            if (!changedAny) {
                logs += Line(
                    "DAILY_HISTORY_STABLE",
                    "date=$date src=${new.source} high=${new.computedHighTemp} low=${new.computedLowTemp} fragments=${fragments.size}",
                    "DEBUG",
                )
            }
        }
        return Plan(inserts, updates, logs)
    }

    /** The conditional write found the row changed by a concurrent writer: left alone, next recompute retries. */
    fun raceLine(update: Update, date: LocalDate) = Line(
        "DAILY_HISTORY_RACE",
        "date=$date src=${update.merged.source} at=${update.merged.locationLat},${update.merged.locationLon} " +
            "expectedUpdatedAt=${update.expectedUpdatedAt} — skipped, row changed concurrently",
        "WARN",
    )
}
