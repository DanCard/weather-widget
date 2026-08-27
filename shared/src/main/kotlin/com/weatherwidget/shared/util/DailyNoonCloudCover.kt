package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
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
 * at sub-precision coordinate fragments — the **freshest** (`fetchedAt`) wins. Callers must pass
 * rows that carry `fetchedAt`; mapping it away silently reinstates first-row-wins.
 *
 * When noon data is missing, assumes **0%** (clear) rather than borrowing a non-noon hour or
 * another API source.
 *
 * The only sanctioned source exception is the climate-normal gap: when the day's stored
 * forecast row is [WeatherSource.GENERIC_GAP] we look at GENERIC_GAP hourly instead.
 */
object DailyNoonCloudCover {

    /**
     * Site-aware variant for callers holding raw proximity-box rows. Selects the freshest row per
     * hour **at the display site** before resolving noon, which additionally excludes a
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
        // Freshest wins among duplicate noon rows, matching HourlyForecastSelector's rule for the
        // same table. One site accumulates several rows per hour at sub-precision coordinate
        // fragments, and unifying to the nearest site does NOT reduce them to one: a fragment
        // 0.001 degrees away is legitimately `LocationMatch.sameSite`, so it survives unification
        // by design. Only fetchedAt separates a current forecast from a five-day-old one at the
        // same site, and taking the first match instead made the daily bar flap between the two
        // as the row order changed with the query window (2026-08-27: 50% vs a 08-22 26%).
        // maxByOrNull keeps the first of equal fetchedAt values, so rows without one (synthesized
        // gap fills, fetchedAt = 0) behave exactly as before.
        return hourly.asSequence()
            .filter { it.source == targetSourceId }
            .filter { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.dateTime), zone) == noon }
            .filter { with(VisibleCloudCover) { it.visibleCloudCover() } != null }
            .maxByOrNull { it.fetchedAt }
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
