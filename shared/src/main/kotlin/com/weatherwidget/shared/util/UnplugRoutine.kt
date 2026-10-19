package com.weatherwidget.shared.util

import com.weatherwidget.data.remote.ForecastHorizonCadence
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

/**
 * When the user will next unplug, learned from past overnight charges — the pre-wake fetch's wake
 * time when no alarm is set ([ForecastHorizonCadence.preWakeAtMs];
 * performance/261019-learned-unplug-time-and-overnight-short-skip.md).
 *
 * Only overnight charges count: at least [MIN_SESSION_HOURS] long and running through [NIGHT_HOUR]
 * local — a phone on a desk's USB from 09:00 to 17:30 is a long charge too, and its 17:30 "unplug"
 * would scatter the morning routine. Weekdays and weekends are judged apart. A day type has a routine when, over the last
 * [LOOKBACK_DAYS], at least [MIN_SAMPLES] unplugs fall within an interquartile range of
 * [MAX_SPREAD_MINUTES]; the prediction is their [PREDICT_PERCENTILE] time of day — early, because a
 * fetch a little early is still fresh and one after the unplug is lost. Times of day are local, so a
 * DST change does not shift the routine. An alarm always wins over this.
 */
object UnplugRoutine {
    /** A charge shorter than this is a top-up, not the night's charge. */
    const val MIN_SESSION_HOURS = 4

    /** An overnight charge runs through this local hour (03:00). */
    const val NIGHT_HOUR = 3
    const val LOOKBACK_DAYS = 21
    const val MIN_SAMPLES = 4
    const val MAX_SPREAD_MINUTES = 60
    const val PREDICT_PERCENTILE = 0.25

    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L

    /** One recorded unplug: when, how long the charge before it lasted, and whether the time is exact. */
    data class Unplug(val atMs: Long, val sessionMinutes: Int, val exact: Boolean)

    enum class DayType { WEEKDAY, WEEKEND }

    /** One day type's spread; [usable] when it is a routine. */
    data class Routine(val dayType: DayType, val samples: Int, val minuteOfDay: Int?, val spreadMinutes: Int?) {
        val usable: Boolean
            get() = minuteOfDay != null && spreadMinutes != null && samples >= MIN_SAMPLES && spreadMinutes <= MAX_SPREAD_MINUTES
    }

    fun dayType(date: LocalDate): DayType =
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) DayType.WEEKEND else DayType.WEEKDAY

    /** Whether [unplug] ended a night's charge: long enough, and charging at [NIGHT_HOUR] local. */
    fun isOvernight(unplug: Unplug, zone: ZoneId): Boolean {
        if (unplug.sessionMinutes < MIN_SESSION_HOURS * 60) return false
        val end = Instant.ofEpochMilli(unplug.atMs).atZone(zone)
        val start = end.minusMinutes(unplug.sessionMinutes.toLong())
        // The latest NIGHT_HOUR at or before the unplug falls inside the session.
        var night = end.toLocalDate().atTime(NIGHT_HOUR, 0).atZone(zone)
        if (night.isAfter(end)) night = night.minusDays(1)
        return !night.isBefore(start)
    }

    fun routine(unplugs: List<Unplug>, dayType: DayType, nowMs: Long, zone: ZoneId): Routine {
        val minutes = unplugs
            .filter { it.atMs <= nowMs && it.atMs >= nowMs - LOOKBACK_DAYS * DAY_MS && isOvernight(it, zone) }
            .map { Instant.ofEpochMilli(it.atMs).atZone(zone) }
            .filter { dayType(it.toLocalDate()) == dayType }
            .map { it.hour * 60 + it.minute }
            .sorted()
        if (minutes.isEmpty()) return Routine(dayType, 0, null, null)
        return Routine(
            dayType = dayType,
            samples = minutes.size,
            minuteOfDay = percentile(minutes, PREDICT_PERCENTILE),
            spreadMinutes = percentile(minutes, 0.75) - percentile(minutes, 0.25),
        )
    }

    /**
     * The next predicted unplug after [nowMs], at most [ForecastHorizonCadence.PRE_WAKE_MAX_AHEAD_HOURS]
     * away; null when that day type has no routine.
     */
    fun predictNextMs(unplugs: List<Unplug>, nowMs: Long, zone: ZoneId): Long? {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val routines = DayType.entries.associateWith { routine(unplugs, it, nowMs, zone) }
        for (date in listOf(today, today.plusDays(1))) {
            val r = routines.getValue(dayType(date)).takeIf { it.usable } ?: continue
            val minute = r.minuteOfDay ?: continue
            val at = date.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
            if (at <= nowMs) continue
            return at.takeIf { it - nowMs <= ForecastHorizonCadence.PRE_WAKE_MAX_AHEAD_HOURS * HOUR_MS }
        }
        return null
    }

    /** `UNPLUG_ROUTINE` message: `weekday=06:42±18m n=9 weekend=none n=2`. */
    fun logLine(unplugs: List<Unplug>, nowMs: Long, zone: ZoneId): String =
        DayType.entries.joinToString(" ") { type ->
            val r = routine(unplugs, type, nowMs, zone)
            val value = if (r.usable) "%02d:%02d±%dm".format(r.minuteOfDay!! / 60, r.minuteOfDay % 60, r.spreadMinutes) else "none"
            "${type.name.lowercase()}=$value n=${r.samples}"
        }

    /** Nearest-rank percentile of sorted [values]. */
    private fun percentile(values: List<Int>, p: Double): Int =
        values[(ceil(p * values.size).toInt() - 1).coerceIn(0, values.size - 1)]
}
