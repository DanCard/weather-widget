package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The daily-history maintenance passes both platforms run after a fetch, whole: the windows, the
 * source scope (every source at the site), which forecast rows each pass reads, the one-time gates
 * and the `app_logs` rows. The rules are [DailyHistoryMaintenance]; the platform ([Host]) only reads
 * and writes rows.
 *
 * Replaces the per-platform glue that had drifted (plans/261021-android-desktop-shared-code-audit.md
 * §2): desktop read only its own source, bounded the `daily_history` reads by local midnight while
 * the rows are keyed by UTC midnight, and settled with a full-row write that could drop a concurrent
 * blend write. Each pass now behaves as Android's did.
 */
class DailyHistoryRun(
    private val host: Host,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    interface Host {
        /** `daily_history` rows at the site whose `date` (UTC midnight) is in [startMs]..[endMs]. */
        suspend fun dailyHistory(startMs: Long, endMs: Long, latitude: Double, longitude: Double): List<DailyHistory>

        /**
         * Every stored `forecasts` row at the site with `targetDate` (UTC midnight) in
         * [startMs]..[endMs], all sources, every fetch — uncollapsed, implausible temperatures nulled.
         */
        suspend fun forecastFetches(startMs: Long, endMs: Long, latitude: Double, longitude: Double): List<DailyHistoryMaintenance.ForecastHistoryRow>

        /** Live `hourly_forecasts` rows at the site in [startMs]..[endMs], all sources. */
        suspend fun hourlyForecasts(startMs: Long, endMs: Long, latitude: Double, longitude: Double): List<HourlyForecast>

        /** As-predicted `hourly_forecast_history` snapshots of [source] in [startMs]..[endMs]. */
        suspend fun hourlyHistory(startMs: Long, endMs: Long, latitude: Double, longitude: Double, source: String): List<HourlyForecast>

        /** Full-row insert-or-replace. */
        suspend fun insertDailyHistory(rows: List<DailyHistory>)

        /** Writes only `forecastHighTemp`, `forecastLowTemp` and `lastWriter` of an existing row. */
        suspend fun updateForecastOverlay(row: DailyHistory)

        /** Writes only `priorForecastHighTemp`, `priorForecastLowTemp` and `lastWriter` of an existing row. */
        suspend fun updatePriorForecast(row: DailyHistory)

        suspend fun isDone(marker: Marker): Boolean

        /** Records that [marker]'s one-time pass ran; [summary] is its outcome line. */
        suspend fun markDone(marker: Marker, summary: String)

        /** When [upkeepIfDue] last ran at all (any site); 0 when never. */
        suspend fun lastUpkeepMs(): Long

        suspend fun markUpkeep(nowMs: Long)

        suspend fun log(tag: String, message: String, level: String)
    }

    enum class Marker { CHANCE_BACKFILL, FROZEN_DISPLAY_BACKFILL }

    /**
     * After every fetch: the rain-chance freeze ([snapshotDisplayedRainChance]), which must see each
     * window while it is open, and the forecast-only row it needs for yesterday — a source without
     * actuals has no row for the freeze to update until one exists. Two days, so cheap.
     */
    suspend fun afterFetch(latitude: Double, longitude: Double, nowMs: Long) {
        ensureForecastOnlyHistoryRows(latitude, longitude, nowMs, lookbackDays = 1)
        snapshotDisplayedRainChance(latitude, longitude, nowMs)
    }

    /**
     * The month-wide passes — forecast-only rows, the one-time backfills, the settle and "yesterday's
     * forecast" freeze — once per [UPKEEP_INTERVAL_MS], on a charger (user, 2026-10-21: they read a
     * month of fetches and desktop runs once per source). Idempotent; until it runs, a past day shows
     * what was frozen before its midnight. Returns whether it ran.
     */
    suspend fun upkeepIfDue(latitude: Double, longitude: Double, nowMs: Long, isCharging: Boolean): Boolean {
        if (!upkeepDue(nowMs, host.lastUpkeepMs(), isCharging)) return false
        ensureForecastOnlyHistoryRows(latitude, longitude, nowMs)
        backfillForecastChanceSnapshotsIfNeeded(latitude, longitude, nowMs)
        backfillFrozenDisplayColumnsIfNeeded(latitude, longitude, nowMs)
        settlePastForecastOverlays(latitude, longitude, nowMs)
        // Marked only once every pass finished: one that threw is retried on the next fetch.
        host.markUpkeep(nowMs)
        host.log(UPKEEP_TAG, "ran=true site=$latitude,$longitude", "INFO")
        return true
    }

    /**
     * Forecast-only rows ([DailyHistoryWriter.FORECAST_ONLY_ROW]) for past (date, source) pairs with a
     * forecast but no `daily_history` row. Idempotent, so it runs after every fetch.
     */
    suspend fun ensureForecastOnlyHistoryRows(
        latitude: Double,
        longitude: Double,
        nowMs: Long,
        lookbackDays: Long = DailyHistoryMaintenance.FORECAST_ONLY_LOOKBACK_DAYS,
    ) {
        val today = localDate(nowMs)
        val startMs = utcMs(today.minusDays(lookbackDays))
        val todayMs = utcMs(today)
        val forecastRows = newestPerDaySource(
            host.forecastFetches(startMs, todayMs + DAY_MS, latitude, longitude), latitude, longitude,
        )
        if (forecastRows.isEmpty()) return
        val existing = host.dailyHistory(startMs, todayMs, latitude, longitude).map { it.date to it.source }.toSet()
        val rows = DailyHistoryMaintenance.planForecastOnlyRows(forecastRows, existing, todayMs, nowMs)
        if (rows.isEmpty()) return
        host.insertDailyHistory(rows)
        host.log(
            FORECAST_ONLY_HISTORY_TAG,
            "created=${rows.size} sources=${rows.map { it.source }.distinct()} " +
                "first=${rows.minOf { it.date }} last=${rows.maxOf { it.date }}",
            "INFO",
        )
    }

    /** Freezes yesterday's and today's displayed rain chance, overlay and noon cloud ([DailyHistoryMaintenance.planSnapshotDisplayedRainChance]). */
    suspend fun snapshotDisplayedRainChance(latitude: Double, longitude: Double, nowMs: Long) {
        val zone = zoneId()
        val today = localDate(nowMs)
        val yesterday = today.minusDays(1)
        val startMs = utcMs(yesterday)
        val endMs = utcMs(today)
        val dailyRows = newestPerDaySource(host.forecastFetches(startMs, endMs, latitude, longitude), latitude, longitude)
        if (dailyRows.isEmpty()) return
        // Today's night window runs 8pm today -> 8am tomorrow, so the hourly reaches a day past tomorrow's midnight.
        val hourly = host.hourlyForecasts(
            yesterday.atStartOfDay(zone).toInstant().toEpochMilli(),
            today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli(),
            latitude,
            longitude,
        )
        val plan = DailyHistoryMaintenance.planSnapshotDisplayedRainChance(
            dailyRows = dailyRows,
            hourly = hourly,
            existing = host.dailyHistory(startMs, endMs, latitude, longitude),
            centerLat = latitude,
            centerLon = longitude,
            nowMs = nowMs,
            zoneId = zone,
        )
        plan.traces.forEach { Log.v(TAG, it) }
        plan.chanceChangeLogs.forEach { host.log(FREEZE_RAIN_CHANCE_TAG, it, "DEBUG") }
        if (plan.rows.isNotEmpty()) host.insertDailyHistory(plan.rows)
    }

    /**
     * Settles each past day's forecast overlay to the last forecast fetched before its extreme, and
     * freezes each day's "yesterday's forecast" — field-limited writes, so a concurrent blend write
     * is never clobbered.
     */
    suspend fun settlePastForecastOverlays(latitude: Double, longitude: Double, nowMs: Long) {
        val today = localDate(nowMs)
        val startMs = utcMs(today.minusDays(DailyHistoryMaintenance.FORECAST_ONLY_LOOKBACK_DAYS))
        val todayMs = utcMs(today)
        // Through today: the "yesterday's forecast" freeze covers today's row too (its anchors are
        // already past); the settle only ever touches days before today.
        val rowsThroughToday = host.dailyHistory(startMs, todayMs, latitude, longitude)
        if (rowsThroughToday.isEmpty()) return
        // Every stored fetch, not each day's newest: the newest is the post-extreme hindcast this
        // step exists to look past.
        val forecastRows = host.forecastFetches(startMs, todayMs, latitude, longitude)
        if (forecastRows.isEmpty()) return

        val priorPlan = DailyHistoryMaintenance.planPriorForecasts(forecastRows, rowsThroughToday, todayMs, zoneId())
        priorPlan.rows.forEach { host.updatePriorForecast(it) }
        priorPlan.logs.forEach { host.log(PRIOR_FORECAST_FREEZE_TAG, it, "INFO") }

        val existing = rowsThroughToday.filter { it.date < todayMs && (it.computedHighAt != null || it.computedLowAt != null) }
        val plan = DailyHistoryMaintenance.planSettledForecastOverlays(forecastRows, existing, todayMs)
        plan.rows.forEach { host.updateForecastOverlay(it) }
        plan.logs.forEach { host.log(FORECAST_OVERLAY_SETTLED_TAG, it, "INFO") }
    }

    /** One-time: the day/night chance snapshot columns of rows from before they existed, from hourly history. */
    suspend fun backfillForecastChanceSnapshotsIfNeeded(latitude: Double, longitude: Double, nowMs: Long) {
        if (host.isDone(Marker.CHANCE_BACKFILL)) return
        val (startMs, endMs) = backfillRange(nowMs)
        val needing = host.dailyHistory(startMs, endMs, latitude, longitude)
            .filter { it.forecastDayPrecipChance == null && it.forecastNightPrecipChance == null }
        val rows = DailyHistoryMaintenance.planChanceBackfill(
            rowsNeedingBackfill = needing,
            historyFor = { date, source -> historyFor(date, endHourNextDay = 8, latitude, longitude, source) },
            centerLat = latitude,
            centerLon = longitude,
            nowMs = nowMs,
            zoneId = zoneId(),
        )
        if (rows.isNotEmpty()) host.insertDailyHistory(rows)
        host.markDone(Marker.CHANCE_BACKFILL, "backfilled=${rows.size} scanned=${needing.size}")
    }

    /** One-time: the frozen display columns (forecast overlay, noon cloud) of rows from before they existed. */
    suspend fun backfillFrozenDisplayColumnsIfNeeded(latitude: Double, longitude: Double, nowMs: Long) {
        if (host.isDone(Marker.FROZEN_DISPLAY_BACKFILL)) return
        val (startMs, endMs) = backfillRange(nowMs)
        // Per-column: a row can already carry noon cloud but no overlay (the live writer runs first),
        // so an all-columns-null gate would skip its overlay forever.
        val needing = host.dailyHistory(startMs, endMs, latitude, longitude)
            .filter { (it.forecastHighTemp == null && it.forecastLowTemp == null) || it.noonCloudPercent == null }
        val rows = if (needing.isEmpty()) {
            emptyList()
        } else {
            DailyHistoryMaintenance.planFrozenDisplayBackfill(
                rowsNeedingBackfill = needing,
                snapshots = host.forecastFetches(startMs, endMs, latitude, longitude),
                historyFor = { date, source -> historyFor(date, endHourNextDay = 0, latitude, longitude, source) },
                centerLat = latitude,
                centerLon = longitude,
                nowMs = nowMs,
                zoneId = zoneId(),
            )
        }
        if (rows.isNotEmpty()) host.insertDailyHistory(rows)
        host.markDone(Marker.FROZEN_DISPLAY_BACKFILL, "backfilled=${rows.size} scanned=${needing.size}")
    }

    /** [date]'s local midnight through [endHourNextDay]:00 the next day. */
    private suspend fun historyFor(
        date: LocalDate,
        endHourNextDay: Int,
        latitude: Double,
        longitude: Double,
        source: String,
    ): List<HourlyForecast> {
        val zone = zoneId()
        return host.hourlyHistory(
            date.atStartOfDay(zone).toInstant().toEpochMilli(),
            date.plusDays(1).atTime(endHourNextDay, 0).atZone(zone).toInstant().toEpochMilli(),
            latitude,
            longitude,
            source,
        )
    }

    private fun backfillRange(nowMs: Long): Pair<Long, Long> {
        val today = localDate(nowMs)
        return utcMs(today.minusDays(DailyHistoryMaintenance.CHANCE_BACKFILL_LOOKBACK_DAYS)) to utcMs(today)
    }

    private fun localDate(nowMs: Long): LocalDate = Instant.ofEpochMilli(nowMs).atZone(zoneId()).toLocalDate()

    companion object {
        private const val TAG = "DailyHistoryRun"
        private const val DAY_MS = 86_400_000L
        const val FORECAST_ONLY_HISTORY_TAG = "FORECAST_ONLY_HISTORY"
        const val FREEZE_RAIN_CHANCE_TAG = "FREEZE_RAIN_CHANCE"
        const val PRIOR_FORECAST_FREEZE_TAG = "PRIOR_FORECAST_FREEZE"
        const val FORECAST_OVERLAY_SETTLED_TAG = "FORECAST_OVERLAY_SETTLED"
        const val UPKEEP_TAG = "DAILY_HISTORY_UPKEEP"
        const val UPKEEP_INTERVAL_MS = 24L * 3_600_000L

        /** [upkeepIfDue]'s gate: on a charger, and a day since the last run. */
        fun upkeepDue(nowMs: Long, lastMs: Long, isCharging: Boolean): Boolean =
            isCharging && nowMs - lastMs >= UPKEEP_INTERVAL_MS

        /** `daily_history.date` / `forecasts.targetDate` key of a local date: its UTC midnight. */
        fun utcMs(date: LocalDate): Long = date.toEpochDay() * DAY_MS

        /**
         * Each (day, source)'s newest batch, same site preferred — what Android's
         * `ForecastDao.getForecastsInRange` returns, derived here from every fetch so both platforms
         * read it the same way.
         */
        fun newestPerDaySource(
            rows: List<DailyHistoryMaintenance.ForecastHistoryRow>,
            latitude: Double,
            longitude: Double,
        ): List<DailyHistoryMaintenance.ForecastHistoryRow> =
            DailyForecastSelector.selectFreshestPerDaySource(
                rows,
                centerLat = latitude,
                centerLon = longitude,
                targetDate = { it.dateMs },
                source = { it.source },
                locationLat = { it.locationLat },
                locationLon = { it.locationLon },
                lastConfirmedAt = { it.lastConfirmedAt },
                fetchedAt = { it.fetchedAt },
            )
    }
}
