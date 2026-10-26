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
