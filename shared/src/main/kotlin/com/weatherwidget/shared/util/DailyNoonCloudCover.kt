package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.SettledForecastPick
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.HourlyForecastSelector
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Single source of truth (Android + desktop) for a day's representative cloud cover: the hourly
 * low-cloud reading at **noon** (12:00 local) on [date], for the **displayed source only**. The
 * total column is used only when a source or legacy row has no separate low-cloud value.
 *
 * When several rows exist for noon — the normal case, since one site accumulates a row per fetch
 * at sub-precision coordinate fragments — [SettledForecastPick] chooses: the newest fetch at or
 * before noon, the same row the hourly graph draws at 12:00. Callers must pass rows that carry
 * `fetchedAt`; mapping it away silently reinstates first-row-wins.
 *
 * When noon data is missing, assumes **0%** (clear) rather than borrowing a non-noon hour or
 * another API source.
 *
 * The only sanctioned source exception is the climate-normal gap: when the day's stored
 * forecast row is [WeatherSource.GENERIC_GAP] we look at GENERIC_GAP hourly instead.
 */
object DailyNoonCloudCover {

    /**
     * A daily column's noon cloud %, both platforms (the daily bar's sky, `AdaptiveBarStrokes`): a
     * past day's frozen value ([frozenPercent], daily_history); today / future uses measured hourly
     * ([hourlyNoon]) so the column always matches the hourly graph's 12:00 point, with the forecast
     * row ([rowPercent], DailyHourlySummaries) as the fallback when hourly noon data is absent.
     * Null = unknown.
     */
    fun forColumn(
        isPast: Boolean,
        frozenPercent: Int?,
        rowPercent: Int?,
        rowIsDisplaySource: Boolean,
        rowIsClimateNormal: Boolean,
        isToday: Boolean = false,
        hourlyNoon: () -> Int?,
    ): Int? {
        if (isPast) frozenPercent?.let { return it }
        val row = rowPercent?.takeIf { !isPast && rowIsDisplaySource && !rowIsClimateNormal }
        return hourlyNoon() ?: row
    }

    /**
     * Site-aware variant for callers holding raw proximity-box rows. Selects the freshest row per
     * hour **at the display site** before resolving noon. Its caller (`DailyHistoryRun`) passes the
     * stitched hourly — one settled row per hour per source — so that step changes nothing there;
     * fed the live table alone it froze a post-noon hindcast
     * (plans/261027-daily-freeze-reads-stitched-hourly.md). It also excludes a
     * genuinely-different neighbouring marker — one far enough away to fail
     * [com.weatherwidget.data.local.LocationMatch.sameSite], which
     * [resolveMeasuredNoonCloudCoverPercent] cannot tell apart from the display site since it has
     * no query centre. Prefer this wherever coordinates are available.
     */
    fun resolveMeasuredNoonCloudCoverPercentAtSite(
        hourly: List<HourlyForecast>,
        date: LocalDate,
        displaySourceId: String,
        centerLat: Double,
        centerLon: Double,
        rowSourceId: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int? {
        val targetSourceId = if (rowSourceId == WeatherSource.GENERIC_GAP.id) {
            WeatherSource.GENERIC_GAP.id
        } else {
            displaySourceId
        }
        val selected = HourlyForecastSelector.selectForecastsByTime(
            rows = hourly,
            displaySourceId = targetSourceId,
            centerLat = centerLat,
            centerLon = centerLon,
        ).values.toList()
        return resolveMeasuredNoonCloudCoverPercent(
            hourly = selected,
            date = date,
            displaySourceId = targetSourceId,
            rowSourceId = targetSourceId,
            zone = zone,
        )
    }

    /**
     * Measured noon cloud cover (0–100) for the target source, or null when noon data is absent.
     * Use this for the daily icon partly-cloudy floor so a missing reading does not downgrade
     * provider wording.
     */
    fun resolveMeasuredNoonCloudCoverPercent(
        hourly: List<HourlyForecast>,
        date: LocalDate,
        displaySourceId: String,
        rowSourceId: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int? {
        val targetSourceId = if (rowSourceId == WeatherSource.GENERIC_GAP.id) {
            WeatherSource.GENERIC_GAP.id
        } else {
            displaySourceId
        }
        val noon = date.atTime(12, 0)
        val noonMs = noon.atZone(zone).toInstant().toEpochMilli()
        val matching = hourly.asSequence()
            .filter { it.source == targetSourceId }
            .filter { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.dateTime), zone) == noon }
            .filter { with(VisibleCloudCover) { it.visibleCloudCover() } != null }
            .toList()
        // No "now" here: a noon that has not come yet has only pre-noon fetches, so the settled
        // rule already returns the newest — the same answer the stitcher's not-started branch gives.
        return SettledForecastPick.pick(matching, noonMs, nowMs = Long.MAX_VALUE)
            ?.let { with(VisibleCloudCover) { it.visibleCloudCover() } }
            ?.coerceIn(0, 100)
    }

    /** Noon cloud cover as 0–100 percent for the target source; 0 when noon data is missing. */
    fun resolveNoonCloudCoverPercent(
        hourly: List<HourlyForecast>,
        date: LocalDate,
        displaySourceId: String,
        rowSourceId: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int = resolveMeasuredNoonCloudCoverPercent(
        hourly, date, displaySourceId, rowSourceId, zone,
    ) ?: 0

    /** Convenience: the same value as a 0.0–1.0 ratio (what the bar renderer wants). */
    fun resolveNoonCloudCoverRatio(
        hourly: List<HourlyForecast>,
        date: LocalDate,
        displaySourceId: String,
        rowSourceId: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Float = resolveNoonCloudCoverPercent(hourly, date, displaySourceId, rowSourceId, zone) / 100f
}
