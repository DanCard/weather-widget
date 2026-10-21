package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Diagnostic: a fetch's daily high/low against the same fetch's hourly temperatures, logged as
 * `DAILY_HOURLY_MISMATCH` on both platforms. Google's day minimum was filed under the wrong morning
 * for 11 days (daily 51°, hourly 57.5°) and nothing noticed — the two series arrive together and
 * were never compared (`plans/261021-google-daily-low-from-hourly-morning.md`).
 *
 * Only days after today whose whole 00:00–24:00 the hourly covers ([DailyHourlySummaries.windowCovered];
 * today's elapsed hours are history, not this fetch's forecast). A high is off when it is more than
 * [TOLERANCE_F] from the day's hourly maximum. Sources disagree on a low's window (the morning, or
 * midnight to midnight), so a low is off only when it is that far from **both** the 00:00–09:00 and
 * the whole day's hourly minimum. All sources fetch °F.
 */
object DailyHourlyConsistency {
    const val LOG_TAG = "DAILY_HOURLY_MISMATCH"
    const val TOLERANCE_F = 3f
    private const val MORNING_END_HOUR = 9

    data class Day(val date: LocalDate, val high: Float?, val low: Float?)

    data class Mismatch(val date: LocalDate, val field: String, val daily: Float, val hourly: Float, val hourlyDay: Float? = null)

    fun check(days: List<Day>, hourly: List<HourlyForecast>, today: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): List<Mismatch> =
        days.filter { it.date > today }.flatMap { day ->
            val start = day.date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = day.date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            if (!DailyHourlySummaries.windowCovered(hourly, start, end)) return@flatMap emptyList()
            val whole = hourly.filter { it.dateTime in start until end }.map { it.temperature }
            val morningEnd = day.date.atTime(MORNING_END_HOUR, 0).atZone(zoneId).toInstant().toEpochMilli()
            val morning = hourly.filter { it.dateTime in start until morningEnd }.map { it.temperature }
            listOfNotNull(
                day.high?.takeIf { it.isFinite() }?.let { high ->
                    val max = whole.max()
                    Mismatch(day.date, "high", high, max).takeIf { abs(high - max) > TOLERANCE_F }
                },
                day.low?.takeIf { it.isFinite() && morning.isNotEmpty() }?.let { low ->
                    val morningMin = morning.min()
                    val dayMin = whole.min()
                    Mismatch(day.date, "low", low, morningMin, dayMin)
                        .takeIf { abs(low - morningMin) > TOLERANCE_F && abs(low - dayMin) > TOLERANCE_F }
                },
            )
        }

    /** One line per fetch: `source=GOOGLE_WEATHER 2026-10-21:low daily=51.0 hourly=57.5 day=57.5; …`. */
    fun logMessage(sourceId: String, mismatches: List<Mismatch>): String =
        "source=$sourceId " + mismatches.joinToString("; ") { m ->
            "${m.date}:${m.field} daily=${fmt(m.daily)} hourly=${fmt(m.hourly)}" + (m.hourlyDay?.let { " day=${fmt(it)}" } ?: "")
        }

    private fun fmt(t: Float) = String.format(java.util.Locale.ROOT, "%.1f", t)
}
