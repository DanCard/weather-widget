package com.weatherwidget.shared.util

import com.weatherwidget.widget.ViewMode

/**
 * Shared tap-target routing for the left cluster of the widget header (weather icon, current
 * temperature/yesterday-delta, and rain %), used by both Android and Desktop so neither platform
 * duplicates view-transition rules (`plans/261119-unify-header-tap-targets-and-radar-isolation.md`).
 */
object HeaderTapRouting {

    enum class Zone { ICON, TEMP_AND_DELTA, PRECIP }

    /**
     * Target [ViewMode] when tapping [zone] from [current]:
     * - [Zone.TEMP_AND_DELTA]: toggles `DAILY ↔ TEMPERATURE` (from any hourly view, returns to `DAILY`).
     * - [Zone.PRECIP]: toggles `PRECIPITATION ↔ DAILY` (from `DAILY`/`TEMPERATURE`/`CLOUD_COVER`, opens `PRECIPITATION`).
     * - [Zone.ICON]: follows [Zone.PRECIP] while rain is actively falling ([isRainingNow] = true),
     *   else follows [Zone.TEMP_AND_DELTA].
     */
    fun target(
        zone: Zone,
        current: ViewMode,
        isRainingNow: Boolean = false,
    ): ViewMode = when (zone) {
        Zone.TEMP_AND_DELTA -> if (current == ViewMode.DAILY) ViewMode.TEMPERATURE else ViewMode.DAILY
        Zone.PRECIP -> if (current == ViewMode.PRECIPITATION) ViewMode.DAILY else ViewMode.PRECIPITATION
        Zone.ICON -> if (isRainingNow) target(Zone.PRECIP, current) else target(Zone.TEMP_AND_DELTA, current)
    }
}
