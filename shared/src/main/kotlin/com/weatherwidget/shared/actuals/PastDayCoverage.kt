package com.weatherwidget.shared.actuals

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A one-hour afternoon probe: a past day whose readings never reach it was cut short. */
const val DAYTIME_COVERAGE_HOUR = 14

/**
 * True when a past [date] has readings but none at or after [daytimeHour] local — its daily_history
 * row (if any) was reduced from a partial, overnight-only slice (the emulator's 54° high on
 * 2026-05-31, the device off from 05:20). Today and future days are never "incomplete", and a day
 * with no readings is missing, not incomplete. Moved from Android's `DailyActualsStore` so the shared
 * [com.weatherwidget.shared.observations.NwsGapFillRun] applies it on both platforms.
 */
fun pastDayLacksAfternoonCoverage(
    obsTimestampsMs: List<Long>,
    date: LocalDate,
    zone: ZoneId,
    today: LocalDate,
    daytimeHour: Int = DAYTIME_COVERAGE_HOUR,
): Boolean {
    if (!date.isBefore(today) || obsTimestampsMs.isEmpty()) return false
    return obsTimestampsMs.none { ms ->
        Instant.ofEpochMilli(ms).atZone(zone).hour >= daytimeHour
    }
}

/**
 * The longest silence official stations may leave in a day before its history is re-pulled: one
 * hourly METAR cycle plus 15 min for a late report. 3 h missed the desktop's wake on 2026-10-27 —
 * asleep 00:40–06:55, the wake fetch's stray KNUQ 03:15 and KSJC 05:53 rows split the night into
 * 2 h 40 m holes, so the fill said need=none and the cloud actuals stopped at 01:00.
 */
const val MAX_OFFICIAL_GAP_MS = 75 * 60_000L

/**
 * True when official-station readings ([obsTimestampsMs]) leave a hole longer than [maxGapMs] in
 * [date]'s span — midnight to the next midnight, or to [nowMs] for today — counting the edges, so a
 * day with no readings at all is one long hole. The desktop slept 00:36–08:22 on 2026-10-26: its
 * NWS row existed, personal stations covered the night through the Synoptic fallback, and the day's
 * low came out 48.0° against the Pixel's back-filled 51.2°. Callers pass only official stations,
 * since personal ones are not what the blend leans on.
 */
fun dayHasOfficialGap(
    obsTimestampsMs: List<Long>,
    date: LocalDate,
    zone: ZoneId,
    nowMs: Long,
    maxGapMs: Long = MAX_OFFICIAL_GAP_MS,
): Boolean {
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), nowMs)
    if (end - start <= maxGapMs) return false
    var previous = start
    for (ts in obsTimestampsMs.filter { it in start until end }.sorted()) {
        if (ts - previous > maxGapMs) return true
        previous = ts
    }
    return end - previous > maxGapMs
}
