package com.weatherwidget.shared.graph

import java.time.LocalDateTime

/**
 * Fixed clock-hour definition of "day" vs "night" for rainfall bucketing.
 *
 * This is intentionally distinct from the sun-position based `isNight` flag used for icon tinting:
 * rainfall day/night totals use a stable wall-clock window so the widget graph and the Statistics
 * history always agree, regardless of season or sunrise/sunset drift.
 *
 * DAY   = 08:00 (inclusive) .. 20:00 (exclusive)
 * NIGHT = 20:00 (inclusive) .. 08:00 (exclusive)
 *
 * Lives in `:shared` because desktop hand-rolled `hour in 8 until 20` inline rather than importing
 * it, which is the same duplication that let the actual-rain selection drift (see
 * [RainPeriodSelection] and plans/260901-share-rain-period-selection.md).
 */
object DayNightHours {
    const val DAY_START_HOUR = 8
    const val DAY_END_HOUR = 20

    /** True when [hour] (0-23) falls in the daytime window. */
    fun isDayHour(hour: Int): Boolean = hour in DAY_START_HOUR until DAY_END_HOUR

    fun isDayHour(dateTime: LocalDateTime): Boolean = isDayHour(dateTime.hour)
}
