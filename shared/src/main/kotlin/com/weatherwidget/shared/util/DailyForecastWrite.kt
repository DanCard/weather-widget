package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One fetch's daily rows for one source at one site, as both platforms store them (Android's rule,
 * user 2026-10-24; `plans/261024-shared-forecast-persist-rules.md`): which rows are written, which
 * existing rows are only re-stamped with this fetch's `lastConfirmedAt`, and the `SNAPSHOT_*` lines.
 *
 * - Past days, climate normals and an already-ended NWS period are not written.
 * - A same-day high/low past its [SameDayExtremeCutoff] keeps the prior row's value (the sent one
 *   goes to `hindcast*`); a missing low for today keeps a recent stored one.
 * - A window this fetch's hourly did not cover keeps the prior row's summary ([DailyHourlySummaries]).
 * - A row with neither high nor low is not written (it would hide an older real row).
 * - A row equal to the prior is not written again; the prior is re-stamped, so readers aging by last
 *   confirmation ([PriorDayForecast]) still see it confirmed — unless a user Refresh, which writes.
 *
 * "Prior" is the newest stored row of the day on this site's own quantized key — never another site
 * in the proximity box. The platform reads [Stored] rows there and writes the [Plan].
 */
object DailyForecastWrite {
    /** One day as this fetch sent it. [periodEndMs]: NWS's daytime period end; null elsewhere. */
    data class Incoming(
        val date: LocalDate,
        val highTemp: Float?,
        val lowTemp: Float?,
        val condition: String,
        val iconToken: String? = null,
        val precipProbability: Int? = null,
        val precipAmountMm: Float? = null,
        val daytimePrecipProbability: Int? = null,
        val nighttimePrecipProbability: Int? = null,
        val summary: DailyHourlySummaries.Summary = DailyHourlySummaries.Summary(),
        val isClimateNormal: Boolean = false,
        val periodEndMs: Long? = null,
    )

    /** The stored values of one row; [ref] is the platform's handle for re-stamping it. */
    data class Values(
        val highTemp: Float?,
        val lowTemp: Float?,
        val condition: String,
        val iconToken: String?,
        val precipProbability: Int?,
        val precipAmountMm: Float?,
        val daytimePrecipProbability: Int?,
        val nighttimePrecipProbability: Int?,
        val hindcastHighTemp: Float?,
        val hindcastLowTemp: Float?,
        val summary: DailyHourlySummaries.Summary,
    )

    /** A stored row of this source on this site's exact quantized key. */
    data class Stored<K>(
        val date: LocalDate,
        val values: Values,
        val dateOfPrediction: Long,
        val lastConfirmedAt: Long,
        val fetchedAt: Long,
        val ref: K,
    )

    /** A row to write, dated [date], with `dateOfPrediction` = today and this fetch's stamps. */
    data class Row(val date: LocalDate, val values: Values)

    data class Line(val tag: String, val message: String, val level: String = "DEBUG")

    data class Plan<K>(val write: List<Row>, val restamp: List<K>, val lines: List<Line>)

    /** The days [incoming] can write, for the platform's [Stored] read; null when none. */
    fun readRange(incoming: List<Incoming>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): ClosedRange<LocalDate>? {
        val today = PredictionDate.of(nowMs, zone)
        val dates = incoming.filter { !it.isClimateNormal && !it.date.isBefore(today) }.map { it.date }
        return if (dates.isEmpty()) null else dates.min()..dates.max()
    }

    fun <K> plan(
        incoming: List<Incoming>,
        stored: List<Stored<K>>,
        sourceId: String,
        nowMs: Long,
        lastConfirmedAt: Long = nowMs,
        userRefresh: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Plan<K> {
        val lines = mutableListOf<Line>()
        val today = PredictionDate.of(nowMs, zone)
        val candidates = incoming.filter { day ->
            if (day.date.isBefore(today) || day.isClimateNormal) return@filter false
            val periodEnd = day.periodEndMs?.let { Instant.ofEpochMilli(it).atZone(zone) }
            if (periodEnd != null && periodEnd.toInstant().toEpochMilli() < nowMs) {
                lines += Line("SNAPSHOT_SKIP_ELAPSED", "date=${day.date} source=$sourceId periodEnd=$periodEnd")
                return@filter false
            }
            true
        }
        if (candidates.isEmpty()) return Plan(emptyList(), emptyList(), lines)

        val newestFirst = compareByDescending<Stored<K>> { it.dateOfPrediction }
            .thenByDescending { it.lastConfirmedAt }
            .thenByDescending { it.fetchedAt }
        val priorByDate = stored.sortedWith(newestFirst).distinctBy { it.date }.associateBy { it.date }
        // Newest stored row per day that has a low: the newest row may have none (a source that no
        // longer reports the night ending this morning), so it cannot be the one to keep from.
        val newestLowByDate = stored.filter { it.values.lowTemp != null }
            .groupBy { it.date }
            .mapValues { (_, rows) -> rows.maxBy { it.fetchedAt } }

        val rows = candidates.mapNotNull { day ->
            val high = day.highTemp?.takeIf { it.isFinite() }
            val low = day.lowTemp?.takeIf { it.isFinite() }
            if (high == null && low == null) return@mapNotNull null
            val isToday = day.date == today
            val filtered = SameDayExtremeCutoff.filter(
                targetDate = day.date,
                highTemp = ForecastTempRounding.forStorage(high, isToday),
                lowTemp = ForecastTempRounding.forStorage(low, isToday),
                nowMs = nowMs,
                zone = zone,
            )
            // Keep the last real pre-cutoff prediction rather than nulling the field: a post-cutoff
            // batch must not become the newest row with a hole where the forecast used to be.
            val prior = priorByDate[day.date]?.values
            val highToStore = if (filtered.frozeHigh) prior?.highTemp else filtered.highTemp
            val lowToStore = if (filtered.frozeLow) {
                prior?.lowTemp
            } else {
                filtered.lowTemp ?: newestLowByDate[day.date].let {
                    SameDayExtremeCutoff.keptTodayLow(isToday, it?.values?.lowTemp, it?.fetchedAt, nowMs)
                }
            }
            // The frozen side's sent value is kept: readers ignore hindcast*, and a past day with no
            // pre-cutoff forecast draws it as a dashed fallback (PastDayForecastOverlay).
            val hindcastHigh = if (filtered.frozeHigh) ForecastTempRounding.forStorage(high, isToday) else null
            val hindcastLow = if (filtered.frozeLow) ForecastTempRounding.forStorage(low, isToday) else null
            if (filtered.frozeAny) {
                lines += Line(
                    "SNAPSHOT_SKIP_HINDCAST",
                    "date=${day.date} source=$sourceId " +
                        "froze=${listOfNotNull("high".takeIf { filtered.frozeHigh }, "low".takeIf { filtered.frozeLow }).joinToString("+")} " +
                        "prior_high=${prior?.highTemp} prior_low=${prior?.lowTemp}",
                )
            }
            if (highToStore == null && lowToStore == null && hindcastHigh == null && hindcastLow == null) {
                return@mapNotNull null
            }
            val summary = DailyHourlySummaries.carryForward(day.summary, prior?.summary)
            val periods = DailyPrecipPeriods.resolve(
                providerDay = day.daytimePrecipProbability,
                providerNight = day.nighttimePrecipProbability,
                hourly = summary,
            )
            Row(
                day.date,
                Values(
                    highTemp = highToStore,
                    lowTemp = lowToStore,
                    condition = day.condition,
                    iconToken = day.iconToken,
                    precipProbability = day.precipProbability,
                    precipAmountMm = day.precipAmountMm,
                    daytimePrecipProbability = periods.day,
                    nighttimePrecipProbability = periods.night,
                    hindcastHighTemp = hindcastHigh,
                    hindcastLowTemp = hindcastLow,
                    summary = summary,
                ),
            )
        }

        val write = mutableListOf<Row>()
        val restamp = mutableListOf<K>()
        for (row in rows) {
            val existing = priorByDate[row.date]
            val new = row.values
            val strictlyBetter = existing != null &&
                ((existing.values.highTemp == null && new.highTemp != null) || (existing.values.lowTemp == null && new.lowTemp != null))
            val unchanged = existing != null && existing.values == new && !strictlyBetter
            val recentDuplicate = existing != null && nowMs - existing.fetchedAt in 0 until 60_000L
            if (unchanged && (!userRefresh || recentDuplicate)) {
                val old = existing!!.values
                lines += Line(
                    "SNAPSHOT_SKIP",
                    "date=${row.date} source=$sourceId " +
                        "existing_high=${old.highTemp} new_high=${new.highTemp} " +
                        "existing_low=${old.lowTemp} new_low=${new.lowTemp} " +
                        "existing_cond=${old.condition} new_cond=${new.condition} " +
                        "existing_precip=${old.precipProbability} new_precip=${new.precipProbability}",
                )
                restamp += existing.ref
            } else {
                if (existing != null && strictlyBetter) {
                    lines += Line(
                        "SNAPSHOT_UPGRADE",
                        "date=${row.date} source=$sourceId " +
                            "existing_high=${existing.values.highTemp} new_high=${new.highTemp} " +
                            "existing_low=${existing.values.lowTemp} new_low=${new.lowTemp}",
                    )
                }
                lines += Line("SNAPSHOT_SAVE", "date=${row.date} source=$sourceId" + if (unchanged && userRefresh) " user_refresh=true" else "")
                write += row
            }
        }
        if (sourceId == WeatherSource.NWS.id) {
            lines += Line(
                "NWS_BATCH_SAVE_SUMMARY",
                "batch=$lastConfirmedAt " +
                    "rawCount=${incoming.size} rawMaxDate=${incoming.maxOfOrNull { it.date }} " +
                    "filteredCount=${rows.size} filteredMaxDate=${rows.maxOfOrNull { it.date }} " +
                    "savedCount=${write.size} savedMaxDate=${write.maxOfOrNull { it.date }} " +
                    "terminal=${rows.maxByOrNull { it.date }?.let { "${it.date} high=${it.values.highTemp} low=${it.values.lowTemp}" }}",
            )
        }
        return Plan(write, restamp, lines)
    }
}
