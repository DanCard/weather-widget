package com.weatherwidget.shared.stats

import com.weatherwidget.data.model.HourlyActual
import com.weatherwidget.data.model.HourlyActualsHistory
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ObservedPrecip
import com.weatherwidget.shared.graph.DayNightHours
import com.weatherwidget.shared.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Rain-amount accuracy for a site, on both platforms: the date window, the prior-day snapshot pick,
 * the shared measured series, pairing, scoring, the per-date ★ and the log line. Each platform's
 * [Host] only reads rows (plans/261113-per-source-rain-score.md).
 *
 * - **Predicted** for an hour = the newest snapshot captured on the hour's previous local calendar
 *   day ("1-day-ahead"; `HistorySnapshotRetention` rule 4 keeps exactly that row).
 * - **Measured** = [ObservedPrecip.measuredRows] through [ObservedPrecip.byHour], the same series for
 *   every source; hours behind raw-observation retention fall back to the NWS `hourly_actuals`
 *   archive, which the archiver built from the same gauges. A live hour always wins.
 */
class RainAccuracyRunner(private val host: Host) {
    interface Host {
        /** [source]'s snapshots for hours in [[startMs], [endMs]) captured in [[bucketStartMs], [bucketEndMs]). */
        suspend fun forecastSnapshots(
            source: WeatherSource,
            startMs: Long,
            endMs: Long,
            bucketStartMs: Long,
            bucketEndMs: Long,
            lat: Double,
            lon: Double,
        ): List<PrecipSnapshot>

        /** Every stored observation at the site in [[startMs], [endMs]), all feeds. */
        suspend fun observations(startMs: Long, endMs: Long, lat: Double, lon: Double): List<ObservationReading>

        /** [sourceId]'s archived hours at the site in [[startMs], [endMs]). */
        suspend fun archivedActuals(startMs: Long, endMs: Long, lat: Double, lon: Double, sourceId: String): List<HourlyActual>
    }

    /** One stored hourly forecast row, reduced to what rain accuracy reads. */
    data class PrecipSnapshot(val dateTime: Long, val timestampToGroupPredictions: Long, val precipAmountMm: Float?)

    suspend fun run(
        sources: List<WeatherSource>,
        lat: Double,
        lon: Double,
        days: Int = 30,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): RainAccuracyReport {
        val lastDate = today.minusDays(1)
        val firstDate = lastDate.minusDays(days.toLong() - 1)
        val windowStartMs = firstDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val windowEndMs = today.atStartOfDay(zone).toInstant().toEpochMilli()

        val measured = measuredByHour(
            observations = host.observations(windowStartMs, windowEndMs, lat, lon),
            archived = host.archivedActuals(windowStartMs, windowEndMs, lat, lon, WeatherSource.NWS.id),
            zone = zone,
        )

        val rows = mutableListOf<DailyRainAccuracy>()
        val comparisons = mutableMapOf<LocalDate, RainScore.DayComparison>()
        val pairedBySource = sources.associateWith { mutableListOf<RainScore.Paired>() }

        var date = lastDate
        while (!date.isBefore(firstDate)) {
            val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEndMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val actual = measured.filterKeys { it.toLocalDate() == date }
            val predBySource = sources.associateWith { source ->
                latestSnapshotPrecipByHour(
                    host.forecastSnapshots(
                        source = source,
                        startMs = dayStartMs,
                        endMs = dayEndMs,
                        bucketStartMs = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                        bucketEndMs = dayStartMs,
                        lat = lat,
                        lon = lon,
                    ),
                    zone,
                )
            }
            val comparison = RainScore.compareDay(predBySource, actual)
            comparisons[date] = comparison
            for (source in sources) {
                val pred = predBySource.getValue(source)
                val paired = RainScore.pair(pred, actual)
                // No hour both forecast and measured: nothing to compare, and nothing scored.
                if (paired.hours == 0) continue
                pairedBySource.getValue(source).add(paired)
                rows.add(dailyRow(date, source, pred, actual, paired, source in comparison.closest))
            }
            date = date.minusDays(1)
        }

        val scores = RainScore.rank(sources.map { RainScore.score(it, pairedBySource.getValue(it)) })
        Log.d(TAG, "RAIN_SCORE $firstDate..$lastDate measuredHours=${measured.size} " + scores.joinToString(" ") {
            "${it.source.id}:days=${it.countedDays},rain=${it.rainDays},mae=${it.meanAbsErrorMm?.let { m -> "%.2f".format(m) }}"
        })
        return RainAccuracyReport(scores, rows, comparisons, firstDate, lastDate)
    }

    companion object {
        private const val TAG = "RainAccuracyRunner"

        /** The measured series: live gauges, with the NWS archive behind them. */
        fun measuredByHour(
            observations: List<ObservationReading>,
            archived: List<HourlyActual>,
            zone: ZoneId = ZoneId.systemDefault(),
        ): Map<LocalDateTime, Float> = HourlyActualsHistory.merge(
            ObservedPrecip.byHour(ObservedPrecip.measuredRows(observations), zone),
            HourlyActualsHistory.rainByHour(archived, zone),
        )

        /** Newest snapshot's amount per local hour; an hour whose newest snapshot has no amount is absent. */
        fun latestSnapshotPrecipByHour(
            rows: List<PrecipSnapshot>,
            zone: ZoneId = ZoneId.systemDefault(),
        ): Map<LocalDateTime, Float> =
            rows.groupBy { hourOf(it.dateTime, zone) }
                .mapNotNull { (hour, hourRows) ->
                    hourRows.maxByOrNull { it.timestampToGroupPredictions }?.precipAmountMm?.let { hour to it }
                }
                .toMap()

        internal fun dailyRow(
            date: LocalDate,
            source: WeatherSource,
            pred: Map<LocalDateTime, Float>,
            actual: Map<LocalDateTime, Float>,
            paired: RainScore.Paired,
            closest: Boolean,
        ): DailyRainAccuracy {
            val hours = pred.keys intersect actual.keys
            fun sum(map: Map<LocalDateTime, Float>, day: Boolean): Float? =
                hours.filter { DayNightHours.isDayHour(it) == day }
                    .takeIf { it.isNotEmpty() }
                    ?.sumOf { map.getValue(it).toDouble() }?.toFloat()
            return DailyRainAccuracy(
                date = date.toString(),
                source = source,
                predDayMm = sum(pred, day = true),
                actualDayMm = sum(actual, day = true),
                predNightMm = sum(pred, day = false),
                actualNightMm = sum(actual, day = false),
                predTotalMm = paired.predMm.takeIf { paired.hours > 0 },
                actualTotalMm = paired.actualMm.takeIf { paired.hours > 0 },
                pairedHours = paired.hours,
                closest = closest,
            )
        }

        private fun hourOf(epochMs: Long, zone: ZoneId): LocalDateTime =
            Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
    }
}
