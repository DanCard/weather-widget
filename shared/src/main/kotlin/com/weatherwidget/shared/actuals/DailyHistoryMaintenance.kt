package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.HourlyForecastStitcher
import com.weatherwidget.shared.util.DailyHistoryFreeze
import com.weatherwidget.shared.util.DailyNoonCloudCover
import com.weatherwidget.shared.util.DailyRainLabels
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Platform-neutral planning for the daily-history maintenance passes that both Android
 * (`DailyHistorySnapshotter`) and desktop (`DesktopWeatherRepository`) run after every fetch:
 *
 * 1. forecast-only rows for past days ([planForecastOnlyRows]);
 * 2. the live freeze of the displayed day's/yesterday's rain chance, forecast overlay and noon
 *    cloud ([planSnapshotDisplayedRainChance]);
 * 3. the one-time chance and frozen-display backfills
 *    ([planChanceBackfill], [planFrozenDisplayBackfill]).
 *
 * The *rules* live here so the two platforms agree exactly; each platform keeps its own DAO reads,
 * entity mapping, one-time gate (Android SharedPreferences / desktop `app_logs` marker) and log
 * sinks. This follows [ForecastOnlyHistoryPlanner]'s "pure planner + platform writer" shape.
 *
 * Before this existed the two implementations had already drifted (a degenerate high==low filter
 * applied on desktop only, missing `lastWriter` stamps on desktop, and Android-only hourly
 * stitching). Unifying the planning removes that class of drift.
 */
object DailyHistoryMaintenance {
    /** Matches the widget's 30-day history nav; the planner keeps only days strictly before today. */
    const val FORECAST_ONLY_LOOKBACK_DAYS = 31L

    /** Default one-time backfill lookback; callers may pass a platform policy value instead. */
    const val CHANCE_BACKFILL_LOOKBACK_DAYS = 30L

    /**
     * One forecast row, flattened from the platform's forecast entity/snapshot type.
     *
     * `fetchedAt` is only needed by the planners that choose the freshest complete batch
     * (forecast-only rows, frozen-display overlay); the live snapshot pass ignores it.
     */
    data class ForecastHistoryRow(
        /** UTC midnight epoch millis of the forecast's target day. */
        val dateMs: Long,
        val source: String,
        val locationLat: Double,
        val locationLon: Double,
        val highTemp: Float?,
        val lowTemp: Float?,
        val precipAmountMm: Float?,
        val condition: String,
        val fetchedAt: Long,
        val isClimateNormal: Boolean,
        val precipProbability: Int? = null,
        val daytimePrecipProbability: Int? = null,
        val nighttimePrecipProbability: Int? = null,
    )

    /** Result of [planSnapshotDisplayedRainChance]: rows to write plus the log lines they imply. */
    data class SnapshotPlan(
        val rows: List<DailyHistory>,
        /** Per-fragment VERBOSE trace lines (`freezeDisplay: …`). */
        val traces: List<String>,
        /** `FREEZE_RAIN_CHANCE` lines, one per fragment whose day/night chance actually changed. */
        val chanceChangeLogs: List<String>,
    )

    /**
     * Forecast-only `daily_history` rows ([DailyHistoryWriter.FORECAST_ONLY_ROW]) for past
     * (date, source) pairs that have a usable forecast batch but no row at all.
     *
     * `computedHighTemp`/`computedLowTemp` stay NULL (no fabricated actuals); the null is the
     * "no actuals" marker that keeps the row out of accuracy baselines.
     */
    fun planForecastOnlyRows(
        forecastRows: List<ForecastHistoryRow>,
        existingKeys: Set<Pair<Long, String>>,
        todayMs: Long,
        nowMs: Long,
    ): List<DailyHistory> {
        val planned = ForecastOnlyHistoryPlanner.plan(
            candidates = forecastRows.map { row ->
                ForecastOnlyHistoryPlanner.Candidate(
                    dateMs = row.dateMs,
                    source = row.source,
                    locationLat = row.locationLat,
                    locationLon = row.locationLon,
                    highTemp = row.highTemp,
                    lowTemp = row.lowTemp,
                    precipAmountMm = row.precipAmountMm,
                    condition = row.condition,
                    fetchedAt = row.fetchedAt,
                    isClimateNormal = row.isClimateNormal,
                )
            },
            existing = existingKeys,
            todayMs = todayMs,
            genericGapSourceId = com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id,
        )
        return planned.map { row ->
            DailyHistory(
                date = row.dateMs,
                source = row.source,
                locationLat = row.locationLat,
                locationLon = row.locationLon,
                computedHighTemp = null,
                computedLowTemp = null,
                condition = row.condition,
                updatedAt = nowMs,
                forecastHighTemp = row.forecastHighTemp,
                forecastLowTemp = row.forecastLowTemp,
                forecastPrecipAmountMm = row.forecastPrecipAmountMm,
                lastWriter = DailyHistoryWriter.FORECAST_ONLY_ROW.storedValue,
            )
        }
    }

    /**
     * Freezes yesterday's and today's displayed rain chance, forecast overlay and measured noon
     * cloud onto their existing `daily_history` fragments, while each freeze window is still open.
     *
     * [dailyRows] is the current forecast (one row per date/source); [hourly] is the live hourly
     * forecast covering yesterday..tomorrow; [existing] is every `daily_history` fragment in the
     * same range. Only rows that already exist are updated.
     */
    fun planSnapshotDisplayedRainChance(
        dailyRows: List<ForecastHistoryRow>,
        hourly: List<HourlyForecast>,
        existing: List<DailyHistory>,
        centerLat: Double,
        centerLon: Double,
        nowMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): SnapshotPlan {
        if (dailyRows.isEmpty() || existing.isEmpty()) {
            return SnapshotPlan(emptyList(), emptyList(), emptyList())
        }
        val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        val yesterday = today.minusDays(1)
        val existingByDateSource = existing.groupBy { it.date to it.source }
        val rows = mutableListOf<DailyHistory>()
        val traces = mutableListOf<String>()
        val chanceChanges = mutableListOf<String>()

        listOf(yesterday, today).forEach { date ->
            val dayWindowOpen = DailyHistoryFreeze.dayWindowOpen(nowMs, date, zoneId)
            val nightWindowOpen = DailyHistoryFreeze.nightWindowOpen(nowMs, date, zoneId)
            // The night window is the last to close (next-day 8am, same as the noon-cloud window;
            // the overlay window closes earlier at midnight), so this early-exit covers every
            // freeze window too.
            if (!dayWindowOpen && !nightWindowOpen) return@forEach
            val overlayOpen = DailyHistoryFreeze.overlayWindowOpen(nowMs, date, zoneId)
            val noonCloudOpen = DailyHistoryFreeze.noonCloudWindowOpen(nowMs, date, zoneId)
            val dateMs = date.toEpochDay() * 86_400_000L

            dailyRows.filter { it.dateMs == dateMs }.forEach { row ->
                val fragments = existingByDateSource[dateMs to row.source].orEmpty()
                if (fragments.isEmpty()) return@forEach
                // ...AtSite: hourly rows are RAW proximity-box rows (jitter fragments included),
                // and the window max is a `max` — one poisoned fragment wins outright.
                val resolved = DailyRainLabels.resolveLiveDayNightChanceAtSite(
                    displaySourceId = row.source,
                    daytimePrecipProbability = row.daytimePrecipProbability,
                    nighttimePrecipProbability = row.nighttimePrecipProbability,
                    precipProbability = row.precipProbability,
                    hourly = hourly,
                    centerLat = centerLat,
                    centerLon = centerLon,
                    targetDate = date,
                    zoneId = zoneId,
                )
                val overlayRow = row.takeIf {
                    DailyHistoryFreeze.isValidOverlayCandidate(
                        isClimateNormal = it.isClimateNormal,
                        sourceId = it.source,
                        highTemp = it.highTemp,
                        lowTemp = it.lowTemp,
                    )
                }
                val resolvedNoonCloud =
                    DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercentAtSite(
                        hourly = hourly,
                        date = date,
                        displaySourceId = row.source,
                        centerLat = centerLat,
                        centerLon = centerLon,
                    )
                fragments.forEach { existingRow ->
                    val newDay = if (dayWindowOpen) {
                        resolved.dayPrecip
                    } else {
                        existingRow.forecastDayPrecipChance
                    }
                    val newNight = if (nightWindowOpen) {
                        resolved.nightPrecip
                    } else {
                        existingRow.forecastNightPrecipChance
                    }
                    val frozen = DailyHistoryFreeze.merge(
                        overlayOpen = overlayOpen,
                        noonCloudOpen = noonCloudOpen,
                        resolvedHigh = overlayRow?.highTemp,
                        resolvedLow = overlayRow?.lowTemp,
                        resolvedPrecipAmountMm = overlayRow?.precipAmountMm,
                        resolvedNoonCloudPercent = resolvedNoonCloud,
                        existing = DailyHistoryFreeze.FrozenDisplay(
                            forecastHighTemp = existingRow.forecastHighTemp,
                            forecastLowTemp = existingRow.forecastLowTemp,
                            forecastPrecipAmountMm = existingRow.forecastPrecipAmountMm,
                            noonCloudPercent = existingRow.noonCloudPercent,
                        ),
                    )
                    val updated = existingRow.copy(
                        lastWriter = DailyHistoryWriter.FORECAST_FREEZE.storedValue,
                        forecastDayPrecipChance = newDay,
                        forecastNightPrecipChance = newNight,
                        forecastHighTemp = frozen.forecastHighTemp,
                        forecastLowTemp = frozen.forecastLowTemp,
                        forecastPrecipAmountMm = frozen.forecastPrecipAmountMm,
                        noonCloudPercent = frozen.noonCloudPercent,
                    )
                    traces += "freezeDisplay: date=$date src=${row.source} " +
                        "overlayOpen=$overlayOpen noonCloudOpen=$noonCloudOpen " +
                        "dayWin=$dayWindowOpen nightWin=$nightWindowOpen " +
                        "dayChance=${existingRow.forecastDayPrecipChance}->$newDay" +
                        "(resolved=${resolved.dayPrecip}) " +
                        "nightChance=${existingRow.forecastNightPrecipChance}->$newNight" +
                        "(resolved=${resolved.nightPrecip}) " +
                        "high=${existingRow.forecastHighTemp}->${updated.forecastHighTemp} " +
                        "low=${existingRow.forecastLowTemp}->${updated.forecastLowTemp} " +
                        "amount=${existingRow.forecastPrecipAmountMm}->" +
                        "${updated.forecastPrecipAmountMm} " +
                        "noonCloud=${existingRow.noonCloudPercent}->${updated.noonCloudPercent}"
                    if (
                        newDay != existingRow.forecastDayPrecipChance ||
                        newNight != existingRow.forecastNightPrecipChance
                    ) {
                        chanceChanges += "date=$date src=${row.source} dayWin=$dayWindowOpen " +
                            "nightWin=$nightWindowOpen resolvedDay=${resolved.dayPrecip} " +
                            "resolvedNight=${resolved.nightPrecip} " +
                            "day=${existingRow.forecastDayPrecipChance}->$newDay " +
                            "night=${existingRow.forecastNightPrecipChance}->$newNight"
                    }
                    if (updated != existingRow) rows += updated
                }
            }
        }
        return SnapshotPlan(rows, traces, chanceChanges)
    }

    /**
     * One-time backfill of the day/night rain-chance snapshot columns from the as-predicted
     * hourly history archive. [historyFor] returns the retained snapshots for a (date, source)
     * window; the planner stitches them the same way the live path does.
     */
    suspend fun planChanceBackfill(
        rowsNeedingBackfill: List<DailyHistory>,
        historyFor: suspend (date: LocalDate, source: String) -> List<HourlyForecast>,
        centerLat: Double,
        centerLon: Double,
        nowMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<DailyHistory> {
        val rows = mutableListOf<DailyHistory>()
        for (row in rowsNeedingBackfill) {
            val date = LocalDate.ofEpochDay(row.date / 86_400_000L)
            val historyRows = historyFor(date, row.source)
            if (historyRows.isEmpty()) continue
            val stitched = HourlyForecastStitcher.stitch(
                current = emptyList(),
                history = historyRows,
                nowMs = nowMs,
                centerLat = centerLat,
                centerLon = centerLon,
            )
            val dayNight = DailyRainLabels.calculateDayNightPrecipProbabilities(
                hourly = stitched,
                targetDate = date,
                displaySourceId = row.source,
                zoneId = zoneId,
            )
            if (dayNight.dayMax == null && dayNight.nightMax == null) continue
            rows += row.copy(
                lastWriter = DailyHistoryWriter.FORECAST_FREEZE.storedValue,
                forecastDayPrecipChance = dayNight.dayMax,
                forecastNightPrecipChance = dayNight.nightMax,
            )
        }
        return rows
    }

    /**
     * One-time backfill of the frozen display columns (forecast overlay + noon cloud). The overlay
     * is the most recent complete non-degenerate snapshot batch for that (date, source) — exactly
     * what the past-day reader selects today. Per-column: a row can carry noon cloud but no overlay,
     * so only missing values are filled.
     */
    suspend fun planFrozenDisplayBackfill(
        rowsNeedingBackfill: List<DailyHistory>,
        snapshots: List<ForecastHistoryRow>,
        historyFor: suspend (date: LocalDate, source: String) -> List<HourlyForecast>,
        centerLat: Double,
        centerLon: Double,
        nowMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<DailyHistory> {
        if (rowsNeedingBackfill.isEmpty()) return emptyList()
        val snapshotsByDateSource = snapshots.groupBy { it.dateMs to it.source }
        val rows = mutableListOf<DailyHistory>()
        for (row in rowsNeedingBackfill) {
            val date = LocalDate.ofEpochDay(row.date / 86_400_000L)
            // Non-degenerate only: a placeholder high==low row must never masquerade as the day's
            // displayed forecast (same rule as DailyHistoryFreeze.isValidOverlayCandidate).
            val overlay = snapshotsByDateSource[row.date to row.source].orEmpty()
                .filter {
                    !it.isClimateNormal &&
                        it.highTemp != null &&
                        it.lowTemp != null &&
                        it.highTemp != it.lowTemp
                }
                .maxByOrNull { it.fetchedAt }
            val historyRows = historyFor(date, row.source)
            val noonCloud = if (historyRows.isEmpty()) {
                null
            } else {
                DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(
                    hourly = HourlyForecastStitcher.stitch(
                        current = emptyList(),
                        history = historyRows,
                        nowMs = nowMs,
                        centerLat = centerLat,
                        centerLon = centerLon,
                    ),
                    date = date,
                    displaySourceId = row.source,
                    zone = zoneId,
                )
            }
            val updated = row.copy(
                lastWriter = DailyHistoryWriter.FORECAST_FREEZE.storedValue,
                forecastHighTemp = row.forecastHighTemp ?: overlay?.highTemp,
                forecastLowTemp = row.forecastLowTemp ?: overlay?.lowTemp,
                forecastPrecipAmountMm = row.forecastPrecipAmountMm ?: overlay?.precipAmountMm,
                noonCloudPercent = row.noonCloudPercent ?: noonCloud,
            )
            if (updated != row) rows += updated
        }
        return rows
    }
}
