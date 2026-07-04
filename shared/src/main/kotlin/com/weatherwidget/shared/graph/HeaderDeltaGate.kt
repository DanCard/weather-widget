package com.weatherwidget.shared.graph

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * When the widget header should show the forecast-bias delta (observed vs. forecast at the same
 * hour). Mirrors [GhostLineGate]'s future-yes/past-no rule: visible while the graph window
 * includes "now" or extends into the future, hidden once the window has scrolled entirely before
 * "now" (there is no ghost line in the past, so there is no delta to anchor it to either).
 */
object HeaderDeltaGate {

    /** The time-only half of the gate, for callers that haven't resolved a delta value yet. */
    fun isWindowVisible(windowEndTime: LocalDateTime, now: LocalDateTime): Boolean =
        !windowEndTime.isBefore(now.truncatedTo(ChronoUnit.HOURS))

    fun isVisible(
        windowEndTime: LocalDateTime,
        now: LocalDateTime,
        appliedDelta: Float?,
        minAbsDelta: Float = 0.1f,
    ): Boolean {
        if (appliedDelta == null || abs(appliedDelta) < minAbsDelta) return false
        return isWindowVisible(windowEndTime, now)
    }
}
