package com.weatherwidget.widget.handlers

import androidx.annotation.VisibleForTesting

/** Pure eligibility and visual-slot policy for the large daily Today overlay. */
internal object DailyLargeTodayOverlayPolicy {
    const val MIN_LAUNCHER_COLUMNS = 10
    const val MIN_LAUNCHER_ROWS = 4
    const val TODAY_SLOT_SPAN = 2

    data class Decision(
        val enabled: Boolean,
        val displayColumns: Int,
    )

    data class Slot(
        val start: Int,
        val span: Int,
    )

    fun resolve(
        launcherColumns: Int,
        launcherRows: Int,
        useGraph: Boolean,
        todayVisible: Boolean,
    ): Decision {
        val enabled =
            useGraph &&
                todayVisible &&
                launcherColumns >= MIN_LAUNCHER_COLUMNS &&
                launcherRows >= MIN_LAUNCHER_ROWS
        return Decision(
            enabled = enabled,
            displayColumns = if (enabled) launcherColumns - 1 else launcherColumns,
        )
    }

    /** Maps logical day columns to the equal launcher slots their bitmap geometry occupies. */
    @VisibleForTesting
    internal fun slots(todayFlags: List<Boolean>, enabled: Boolean): List<Slot> =
        slots(todayFlags.indices.toList(), todayFlags, enabled)

    /** Preserves missing logical columns while inserting one additional slot after Today. */
    @VisibleForTesting
    internal fun slots(
        columnIndices: List<Int>,
        todayFlags: List<Boolean>,
        enabled: Boolean,
    ): List<Slot> {
        val todayColumn =
            columnIndices.zip(todayFlags).firstOrNull { (_, isToday) -> isToday }?.first
        return columnIndices.zip(todayFlags).map { (columnIndex, isToday) ->
            val span = if (enabled && isToday) TODAY_SLOT_SPAN else 1
            val insertedTodaySlot = if (enabled && todayColumn != null && columnIndex > todayColumn) 1 else 0
            Slot(start = columnIndex + insertedTodaySlot, span = span)
        }
    }
}
