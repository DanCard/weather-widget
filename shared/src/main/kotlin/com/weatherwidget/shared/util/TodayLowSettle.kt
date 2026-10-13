package com.weatherwidget.shared.util

import com.weatherwidget.util.SunPositionUtils
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * When today's overnight low is **settled**, so the today column's low label prints the observed
 * actual instead of `min(observed, forecast)` ([DailyDayValueResolver.effectiveLowForLabel]).
 * One rule for Android and desktop (user, 2026-10-13):
 *
 * 1. **Sunrise** — from `sunrise + 30 min` at the location (the low comes around dawn), settled
 *    when no hourly forecast in the next [LOOKAHEAD_HOURS] is below the observed low: the
 *    morning is still forecast to rise. A tie is settled; no hours in the window → settled.
 *    Follows season, DST and latitude, where the fixed 09:00 was ~1.5 h late in summer.
 * 2. **09:00** — settled regardless of the forecast; also the whole rule when there is no sunrise
 *    (no location, polar day/night). The look-ahead is short on purpose: after a warm night the
 *    evening is routinely forecast below the morning low, and a look-ahead to midnight would
 *    hold the forecast number all day — exactly the morning this rule exists to fix.
 * 3. **Actual** — an observed low exists; a forecast stand-in is never settled.
 *
 * See `plans/261013-today-low-settles-at-sunrise-plus-30-and-forecast-rise.md`.
 */
object TodayLowSettle {
    private const val TAG = "TodayLowSettle"

    const val SUNRISE_OFFSET_MINUTES = 30L

    /** Settled from this hour whatever the forecast does; the only cutoff when there is no sunrise. */
    const val ABSOLUTE_CUTOFF_HOUR = 9

    /** How far ahead the forecast must not fall below the observed low, between sunrise + 30 and 09:00. */
    const val LOOKAHEAD_HOURS = 3L

    enum class Reason { SETTLED, SETTLED_AT_NINE, BEFORE_CUTOFF, NO_ACTUAL, FORECAST_FALLS }

    data class Decision(
        val settled: Boolean,
        /** Local cutoff in the caller's zone. */
        val cutoff: LocalDateTime,
        /** True when [cutoff] came from sunrise, false for the [ABSOLUTE_CUTOFF_HOUR] fallback. */
        val fromSunrise: Boolean,
        val reason: Reason,
        /** The lowest hourly forecast in the next [LOOKAHEAD_HOURS], when any. */
        val lookaheadForecastMin: Float?,
    )

    /**
     * Sunrise + [SUNRISE_OFFSET_MINUTES] on [date] at ([lat], [lon]), in [zone]; null when there is
     * no sunrise to compute (no or invalid location, polar day/night).
     *
     * For a location pinned in another time zone the result may fall on another local date (Kyiv's
     * sunrise is the previous evening in California). That is the honest answer: "today" is the
     * device's day, and by then that site's morning has passed.
     */
    fun sunriseCutoff(date: LocalDate, lat: Double?, lon: Double?, zone: ZoneId): LocalDateTime? {
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val sunrise = SunPositionUtils.sunriseInstant(date, lat, lon) ?: return null
        return sunrise.plusSeconds(SUNRISE_OFFSET_MINUTES * 60).atZone(zone).toLocalDateTime()
    }

    /**
     * Decides for the today column at [now]. [todayHourly] is the display source's hourly forecast
     * (already filtered to source; only hours in `(now, now + LOOKAHEAD_HOURS]` count).
     */
    fun <T> decide(
        now: LocalDateTime,
        lat: Double?,
        lon: Double?,
        zone: ZoneId,
        actualLow: Float?,
        todayHourly: List<T>,
        epochMs: (T) -> Long,
        temperature: (T) -> Float,
    ): Decision {
        val today = now.toLocalDate()
        val nine = today.atTime(ABSOLUTE_CUTOFF_HOUR, 0)
        val sunrise = sunriseCutoff(today, lat, lon, zone)
        val cutoff = sunrise?.takeIf { it.isBefore(nine) } ?: nine
        val horizon = now.plusHours(LOOKAHEAD_HOURS)
        val lookaheadMin = todayHourly
            .filter {
                val t = Instant.ofEpochMilli(epochMs(it)).atZone(zone).toLocalDateTime()
                t.isAfter(now) && !t.isAfter(horizon)
            }
            .minOfOrNull(temperature)
        val reason = when {
            now.isBefore(cutoff) -> Reason.BEFORE_CUTOFF
            actualLow == null -> Reason.NO_ACTUAL
            !now.isBefore(nine) -> Reason.SETTLED_AT_NINE
            lookaheadMin != null && lookaheadMin < actualLow -> Reason.FORECAST_FALLS
            else -> Reason.SETTLED
        }
        val settled = reason == Reason.SETTLED || reason == Reason.SETTLED_AT_NINE
        return Decision(settled, cutoff, sunrise != null && cutoff == sunrise, reason, lookaheadMin)
            .also { logOnChange(today, it, actualLow) }
    }

    @Volatile private var lastLogged: String? = null

    /** One line per change of decision — paints repeat every minute and would otherwise flood. */
    private fun logOnChange(today: LocalDate, d: Decision, actualLow: Float?) {
        val key = "$today ${d.reason} ${d.cutoff}"
        if (key == lastLogged) return
        lastLogged = key
        Log.i(
            TAG,
            "TODAY_LOW_SETTLED settled=${d.settled} reason=${d.reason} " +
                "cutoff=${d.cutoff.toLocalTime()} from=${if (d.fromSunrise) "sunrise" else "nine"} " +
                "actualLow=$actualLow lookaheadMin=${d.lookaheadForecastMin}",
        )
    }
}
