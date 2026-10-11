package com.weatherwidget.data.remote

import com.weatherwidget.shared.util.BatteryTier

/**
 * How often a source's hourly forecast is refetched in the background — ONE rule for Android and
 * desktop (user, 2026-10-11; performance/261011-hourly-cadence-by-last-view-and-fuzzy-full-window.md).
 * Daily, current and actuals keep [com.weatherwidget.shared.util.ForecastCadence]; this is hourly only.
 *
 * | Battery | Displayed | Other sources, by time since last view |
 * |---|---|---|
 * | Charging (or ≥ 80 %) | 4 h | ≤ 24 h → 4 h · ≤ 36 h → 6 h · ≤ 48 h → 8 h · ≤ 72 h → 12 h · older → none |
 * | > 50 % | 8 h | ≤ 24 h → 8 h · older → none |
 * | ≤ 50 % | 24 h | none |
 *
 * "none" = no routine hourly fetch; on-demand hourly (a day tap, the gap-fill, a switch while an
 * hourly view shows, explicit Refresh) never asks this rule.
 */
object HourlyCadence {
    private const val HOUR_MS = 3_600_000L

    const val DISPLAYED_CHARGING_HOURS = 4
    const val DISPLAYED_BATTERY_HOURS = 8
    const val DISPLAYED_LOW_BATTERY_HOURS = 24

    /** View-age bands for a non-displayed source on the charger: (max age h, interval h). */
    private val CHARGING_OTHER_BANDS = listOf(24 to 4, 36 to 6, 48 to 8, 72 to 12)

    /** A non-displayed source on battery is refetched only if viewed within this many hours. */
    const val BATTERY_OTHER_MAX_VIEW_AGE_HOURS = 24
    const val BATTERY_OTHER_HOURS = 8

    /** Oldest view that still earns a routine hourly fetch. Also the grace stand-in below. */
    const val MAX_VIEW_AGE_HOURS = 72

    /** While view tracking is younger than this, a never-viewed source counts as viewed [MAX_VIEW_AGE_HOURS] ago. */
    const val GRACE_TRACKING_DAYS = 3L

    /** Same slack as `ForecastFetchPolicy.isDue`: a tick that lands a moment early still counts. */
    const val DUE_GRACE_MS = 120_000L

    /**
     * Hours between hourly fetches of a source, or null for none.
     *
     * @param viewAgeMs time since the user last switched to the source ([SourceLastView]); null when
     *   never (or not within what is kept). Ignored for the displayed source.
     */
    fun intervalHours(
        isCharging: Boolean,
        batteryLevel: Int,
        isDisplayed: Boolean,
        viewAgeMs: Long?,
    ): Int? {
        val charging = BatteryTier.treatAsCharging(isCharging, batteryLevel)
        val aboveLow = batteryLevel > BatteryTier.TIER_MEDIUM_THRESHOLD
        if (isDisplayed) {
            return when {
                charging -> DISPLAYED_CHARGING_HOURS
                aboveLow -> DISPLAYED_BATTERY_HOURS
                else -> DISPLAYED_LOW_BATTERY_HOURS
            }
        }
        val age = viewAgeMs?.coerceAtLeast(0L) ?: return null
        return when {
            charging -> CHARGING_OTHER_BANDS.firstOrNull { (maxHours, _) -> age <= maxHours * HOUR_MS }?.second
            aboveLow -> BATTERY_OTHER_HOURS.takeIf { age <= BATTERY_OTHER_MAX_VIEW_AGE_HOURS * HOUR_MS }
            else -> null
        }
    }

    /**
     * The view age [intervalHours] reads: since [lastViewedAtMs], or — while tracking is in its first
     * [GRACE_TRACKING_DAYS] days and the source was never viewed — [MAX_VIEW_AGE_HOURS], so a fresh
     * install does not stop fetching sources before it knows anything.
     */
    fun viewAgeMs(lastViewedAtMs: Long?, inGrace: Boolean, nowMs: Long): Long? = when {
        lastViewedAtMs != null -> nowMs - lastViewedAtMs
        inGrace -> MAX_VIEW_AGE_HOURS * HOUR_MS
        else -> null
    }

    /** Due when [intervalHours] is set and the last hourly fetch at the site is that old, or there was none. */
    fun isDue(lastHourlyFetchMs: Long?, intervalHours: Int?, nowMs: Long): Boolean =
        ForecastSchedule.isDue(lastHourlyFetchMs, intervalMs(intervalHours), nowMs)

    fun intervalMs(intervalHours: Int?): Long? = intervalHours?.let { it * HOUR_MS }

    /** True while view tracking has fewer than [GRACE_TRACKING_DAYS] days to judge by (or none at all). */
    fun inGrace(trackingSince: java.time.LocalDate?, today: java.time.LocalDate): Boolean =
        trackingSince == null || trackingSince.isAfter(today.minusDays(GRACE_TRACKING_DAYS))

    /** One source's hourly decision, with what it was made from (for the log line). */
    data class Decision(val intervalHours: Int?, val viewAgeMs: Long?, val lastHourlyFetchMs: Long?, val due: Boolean)

    fun decide(
        isCharging: Boolean,
        batteryLevel: Int,
        isDisplayed: Boolean,
        lastViewedAtMs: Long?,
        inGrace: Boolean,
        lastHourlyFetchMs: Long?,
        nowMs: Long,
    ): Decision {
        val age = viewAgeMs(lastViewedAtMs, inGrace, nowMs)
        val interval = intervalHours(isCharging, batteryLevel, isDisplayed, age)
        return Decision(interval, age, lastHourlyFetchMs, isDue(lastHourlyFetchMs, interval, nowMs))
    }

    /** `HOURLY_CADENCE` app_logs fragment for one source. */
    fun logFragment(sourceId: String, intervalHours: Int?, viewAgeMs: Long?, lastHourlyFetchMs: Long?, nowMs: Long, due: Boolean): String =
        "$sourceId:" + (intervalHours?.let { "${it}h" } ?: "none") +
            "(view=${viewAgeMs?.let { "${it / HOUR_MS}h" } ?: "never"}," +
            "lastHourly=${lastHourlyFetchMs?.let { "${(nowMs - it) / HOUR_MS}h" } ?: "none"},due=$due)"
}
