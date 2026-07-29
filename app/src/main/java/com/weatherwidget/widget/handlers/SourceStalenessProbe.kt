package com.weatherwidget.widget.handlers

import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.WidgetQueryWindows
import com.weatherwidget.widget.ZoomLevel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Decides whether switching to a [WeatherSource] should trigger a forced network fetch.
 *
 * Split out of [WidgetIntentRouter] (2026-07-28, third-pass review N7) so the router stops being
 * a single 1400-line surface and so the staleness policy lives next to the DB query that feeds it.
 * The policy half ([sourceNeedsRefresh]) is pure and unit-tested in
 * [com.weatherwidget.widget.handlers.SourceNeedsRefreshTest]; the DB half ([sourceWindowState])
 * is exercised by the Robolectric API-toggle integration test.
 *
 * The two halves are split because the policy is the interesting bit (when do we force a fetch?)
 * while the DB query is just plumbing. Tests on the policy run without a database harness.
 */
object SourceStalenessProbe {
    /**
     * How many days of past daily rows the probe asks the DAO for. Matches
     * [WidgetIntentRouter.DAILY_LOOKBACK_DAYS] by convention — if they ever diverge, having two
     * named constants makes the divergence explicit rather than a silent magic number.
     */
    private const val SOURCE_CHECK_LOOKBACK_DAYS = 30L

    /**
     * How far into the future the probe asks the DAO for daily rows. Smaller than the daily
     * *render* horizon ([WidgetIntentRouter.DAILY_FORECAST_DAYS]): the toggle path only needs to
     * know whether the source has *enough* future coverage to display, not whether it can fill a
     * 30-day bar graph.
     */
    private const val SOURCE_CHECK_FORECAST_DAYS = 14L

    /**
     * Minimum future coverage required for the source to count as "populated". Stricter than
     * `hasDaily` (which is just non-emptiness) — a source missing day-after-tomorrow is treated
     * as not-yet-useful even if it has a few rows. Looser than the render horizon.
     */
    private const val SOURCE_CHECK_MIN_FUTURE_COVERAGE_DAYS = 2L

    /**
     * Switching to a source whose cached data is older than this forces a targeted network fetch.
     * Also acts as the per-source cooldown for repeated toggling.
     */
    @VisibleForTesting
    internal const val TOGGLE_REFRESH_STALE_MS = 15 * 60 * 1000L

    /**
     * Snapshot of what one source has cached for the currently-displayed window. Split from the
     * [sourceNeedsRefresh] decision so the policy is unit-testable without a database.
     */
    @VisibleForTesting
    internal data class SourceWindowState(
        val hasDaily: Boolean,
        val hasHourly: Boolean,
        val hasRequiredFutureCoverage: Boolean,
        // Newest fetchedAt across both streams — i.e. when this source was last fetched at all.
        // Null when the source has no rows in the window.
        val newestFetchedAtMs: Long?,
        // Successful provider check, independent of whether unchanged content rewrote a DB row.
        val lastSuccessfulFetchAtMs: Long? = null,
    )

    /**
     * True when switching to this source should trigger a forced network fetch: its data is either
     * absent/incomplete, or older than [TOGGLE_REFRESH_STALE_MS].
     *
     * The age check doubles as the per-source cooldown — once a toggle-triggered fetch lands,
     * fetchedAt is young, so toggling away and back is a no-op.
     */
    @VisibleForTesting
    internal fun sourceNeedsRefresh(
        state: SourceWindowState,
        nowMs: Long,
    ): Boolean {
        if (!state.hasDaily || !state.hasHourly || !state.hasRequiredFutureCoverage) return true
        val fetchedAt =
            listOfNotNull(state.newestFetchedAtMs, state.lastSuccessfulFetchAtMs).maxOrNull()
                ?: return true
        return nowMs - fetchedAt >= TOGGLE_REFRESH_STALE_MS
    }

    /**
     * Queries the DB to build a [SourceWindowState] for one source.
     *
     * `centerTime`/`zoom` select the graph-window hourly path; when either is null the probe falls
     * back to a fixed lookback/lookahead window around `now`. Callers that render via
     * [WidgetStateManager.resolveHourlyCenterTime] MUST pass the resolved center here too — see
     * the comment in `handleToggleApiInternal` for the bug that motivated this.
     */
    @VisibleForTesting
    internal suspend fun sourceWindowState(
        forecastDao: ForecastDao,
        hourlyDao: HourlyForecastDao,
        hourlyHistoryDao: HourlyForecastHistoryDao,
        lat: Double,
        lon: Double,
        source: WeatherSource,
        centerTime: LocalDateTime? = null,
        zoom: ZoomLevel? = null,
        now: LocalDateTime = LocalDateTime.now(),
        lastSuccessfulFetchAtMs: Long? = null,
    ): SourceWindowState {
        // Hoist `today` once from the caller-supplied `now`: an earlier form called LocalDate.now()
        // three times in six lines, which is a tick-boundary hazard (and untestable). Using now's
        // date also keeps the daily range on the same day as the hourly window computed below.
        val today = now.toLocalDate()
        val historyStart = today.minusDays(SOURCE_CHECK_LOOKBACK_DAYS).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val futureEnd = today.plusDays(SOURCE_CHECK_FORECAST_DAYS).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
        val sourceDaily = forecastDao.getForecastsInRangeBySource(historyStart, futureEnd, lat, lon, source.id)
        val maxDailyDate =
            sourceDaily.map { LocalDate.ofEpochDay(it.targetDate / WeatherTimeUtils.MILLIS_PER_DAY) }.maxOrNull()
        val hasRequiredFutureCoverage = maxDailyDate != null &&
            !maxDailyDate.isBefore(today.plusDays(SOURCE_CHECK_MIN_FUTURE_COVERAGE_DAYS))

        val sourceHourly =
            if (centerTime != null && zoom != null) {
                GraphDataLoader.loadGraphWindowHourlyForecasts(
                    hourlyDao = hourlyDao,
                    hourlyHistoryDao = hourlyHistoryDao,
                    lat = lat,
                    lon = lon,
                    centerTime = centerTime,
                    zoom = zoom,
                    now = now,
                    source = source,
                )
            } else {
                val zoneId = ZoneId.systemDefault()
                val hourlyStart = now.minusHours(WidgetQueryWindows.HOURLY_LOOKBACK_HOURS).atZone(zoneId).toInstant().toEpochMilli()
                val hourlyEnd = now.plusHours(WidgetQueryWindows.HOURLY_GRAPH_LOOKAHEAD_HOURS).atZone(zoneId).toInstant().toEpochMilli()
                // Unify so a frozen fragment from an earlier GPS fix can't satisfy this
                // "has hourly data?" gate when the current site actually has none.
                GraphDataLoader.unifyToNearestSite(
                    hourlyDao.getHourlyForecastsBySource(hourlyStart, hourlyEnd, lat, lon, source.id),
                    lat,
                    lon,
                )
            }

        // max, not min: this means "when was this source last fetched". Some sources populate one
        // stream more sparsely than the other; taking the min would mark them permanently stale and
        // refetch on every single toggle.
        val newestFetchedAt =
            listOfNotNull(
                sourceDaily.maxOfOrNull { it.fetchedAt },
                sourceHourly.maxOfOrNull { it.fetchedAt },
            ).maxOrNull()

        return SourceWindowState(
            hasDaily = sourceDaily.isNotEmpty(),
            hasHourly = sourceHourly.isNotEmpty(),
            hasRequiredFutureCoverage = hasRequiredFutureCoverage,
            newestFetchedAtMs = newestFetchedAt,
            lastSuccessfulFetchAtMs = lastSuccessfulFetchAtMs,
        )
    }
}
