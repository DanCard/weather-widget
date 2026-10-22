package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HistoricalDataKind
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.DayNightHours
import com.weatherwidget.shared.observations.MetarDecoder
import com.weatherwidget.shared.observations.NwsBlend
import com.weatherwidget.shared.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Observed rain for one site: one amount per hour, then totals — the rule behind `daily_history`
 * rain, the precip graph's "Act" labels and the rain accuracy list, on both platforms.
 *
 * Rain is a total over time, so hours add up. Stations do NOT: three airports that caught the same
 * 3 mm shower are 3 mm at the site, not 9 mm. Each hour takes the **nearest station with a known
 * amount** — a real gauge reading; showers are too local for a distance-weighted mean to be more
 * honest (plans/261021-observed-rain-combine-stations-not-sum.md).
 *
 * Within one station:
 * - **Report stations** (airport METARs, NWS station rows): an amount is a running total since the
 *   station's last routine report. A SPECI's `P` is repeated in the next routine report (KMIA
 *   `200845Z … P0002` then `200853Z … P0002`, verified 2026-10-21), so a reporting period takes its
 *   **largest** amount, never the sum. An AO2 report (rain gauge) with no `P` group and no `PNO` is
 *   **measured dry (0)** — without that, combining would average over wet stations only.
 * - **Interval series** (`<SOURCE>_MAIN`, Tomorrow.io's five-minute history): each row is its own
 *   interval's amount, so an hour adds them, as before.
 */
object ObservedPrecip {
    /** Farther than this, a station says nothing about rain at the site. */
    const val MAX_STATION_KM = 25f

    /** A station's routine minute needs this many raw reports to be trusted. */
    private const val MIN_REPORTS_FOR_ROUTINE_MINUTE = 3

    private const val TAG = "ObservedPrecip"

    data class Totals(val total: Float?, val day: Float?, val night: Float?)

    /**
     * The rows that are [source]'s own observed-rain series. A source without a historical
     * precipitation product has none: Silurian's past hours are forecast output, and relabelling
     * them "Actual" would lie about provenance.
     */
    fun rowsFor(rows: List<ObservationReading>, source: WeatherSource): List<ObservationReading> {
        if (!source.historicalDataKind.preservesHistoricalPrecipitation) return emptyList()
        return rows.filter { row ->
            row.api == source.id && when (source) {
                WeatherSource.NWS -> row.stationId != NwsBlend.STATION_ID
                WeatherSource.TOMORROW_IO -> TomorrowIoActuals.isAllowedStation(row.stationId)
                else -> row.stationId == HistoricalActualsBackfill.syntheticStationId(source.id)
            }
        }
    }

    /**
     * The site's amount per local hour, for hours where some station within [MAX_STATION_KM] knew
     * it. Hours nobody knew are absent, not 0.
     */
    fun byHour(rows: List<ObservationReading>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDateTime, Float> {
        val decoded = HashMap<String, MetarDecoder.MetarRemarks?>()
        // hour -> (distance, amount) of the nearest station that knew it.
        val nearest = HashMap<LocalDateTime, Pair<Float, Float>>()
        for ((_, stationRows) in rows.groupBy { it.stationId to it.api }) {
            val distance = stationRows.minOf { it.distanceKm }
            if (distance > MAX_STATION_KM) continue
            val amounts = if (isIntervalSeries(stationRows.first())) {
                intervalHours(stationRows, zone)
            } else {
                reportHours(stationRows, zone, decoded)
            }
            for ((hour, amount) in amounts) {
                val best = nearest[hour]
                if (best == null || distance < best.first) nearest[hour] = distance to amount
            }
        }
        return nearest.mapValues { it.value.second }
    }

    /** [date]'s total, 8am–8pm and night amounts from [byHour]; each null when no hour in it was known. */
    fun daily(rows: List<ObservationReading>, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Totals {
        val hours = byHour(rows, zone).filterKeys { it.toLocalDate() == date }
        fun sumOrNull(values: Collection<Float>) = values.takeIf { it.isNotEmpty() }?.sum()
        val totals = Totals(
            total = sumOrNull(hours.values),
            day = sumOrNull(hours.filterKeys { DayNightHours.isDayHour(it) }.values),
            night = sumOrNull(hours.filterKeys { !DayNightHours.isDayHour(it) }.values),
        )
        Log.v(TAG) {
            "OBSERVED_PRECIP date=$date stations=${rows.map { it.stationId }.distinct().size} " +
                "hours=${hours.size} total=${totals.total} day=${totals.day} night=${totals.night}"
        }
        return totals
    }

    /** Provider history re-filed as one synthetic station: each row is its own interval. */
    private fun isIntervalSeries(row: ObservationReading): Boolean =
        row.rawMetar == null &&
            (row.stationId.endsWith(SYNTHETIC_SUFFIX) || TomorrowIoActuals.isAllowedStation(row.stationId)) &&
            WeatherSource.fromId(row.api).historicalDataKind != HistoricalDataKind.STATION_OBSERVATION

    private const val SYNTHETIC_SUFFIX = "_MAIN"

    private fun intervalHours(rows: List<ObservationReading>, zone: ZoneId): Map<LocalDateTime, Float> =
        rows.mapNotNull { row -> row.precipAmountMm?.let { hourOf(row.timestamp, zone) to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, amounts) -> amounts.sum() }

    private fun reportHours(
        rows: List<ObservationReading>,
        zone: ZoneId,
        decoded: MutableMap<String, MetarDecoder.MetarRemarks?>,
    ): Map<LocalDateTime, Float> {
        val routineMinute = routineMinute(rows, zone)
        val byPeriod = HashMap<LocalDateTime, Float>()
        for (row in rows) {
            if (row.qcFailed) continue
            val amount = row.precipAmountMm ?: measuredDry(row, decoded) ?: continue
            val period = periodOf(row.timestamp, routineMinute, zone)
            byPeriod[period] = maxOf(byPeriod[period] ?: amount, amount)
        }
        return byPeriod
    }

    /** 0 for an AO2 report with no `P` group (and a working gauge); null when it says nothing. */
    private fun measuredDry(row: ObservationReading, decoded: MutableMap<String, MetarDecoder.MetarRemarks?>): Float? {
        val raw = row.rawMetar ?: return null
        val remarks = decoded.getOrPut(raw) { MetarDecoder.decode(raw)?.remarks } ?: return null
        return 0f.takeIf { remarks.hasPrecipDiscriminator && !remarks.precipSensorOff && remarks.hourlyPrecipMm == null }
    }

    /**
     * The minute a station files its routine reports (KSJC :53, KNUQ :55) — the most common minute
     * among its raw reports. api.weather.gov drops the METAR/SPECI word, so this is how a SPECI is
     * told apart there. Null when there are too few reports to say.
     */
    private fun routineMinute(rows: List<ObservationReading>, zone: ZoneId): Int? {
        val minutes = rows.filter { it.rawMetar != null }
            .map { Instant.ofEpochMilli(it.timestamp).atZone(zone).minute }
        if (minutes.size < MIN_REPORTS_FOR_ROUTINE_MINUTE) return null
        return minutes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    /**
     * The hour a report's amount belongs to: the hour of the routine report that closes its
     * period. A 12:58 SPECI counts toward the period the 13:53 report closes, not 12:53's. Without a
     * routine minute, the report's own hour.
     */
    private fun periodOf(timestampMs: Long, routineMinute: Int?, zone: ZoneId): LocalDateTime {
        val at = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDateTime()
        val hour = at.truncatedTo(ChronoUnit.HOURS)
        return if (routineMinute != null && at.minute > routineMinute) hour.plusHours(1) else hour
    }

    private fun hourOf(timestampMs: Long, zone: ZoneId): LocalDateTime =
        Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
}
