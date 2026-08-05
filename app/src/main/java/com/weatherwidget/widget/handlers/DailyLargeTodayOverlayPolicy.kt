package com.weatherwidget.widget.handlers

import androidx.annotation.VisibleForTesting
import com.weatherwidget.shared.graph.LargeTodayOverlayPolicy

/** Pure eligibility and visual-slot policy for the large daily Today overlay. */
internal object DailyLargeTodayOverlayPolicy {
    const val MIN_LAUNCHER_COLUMNS = 10
    const val MIN_LAUNCHER_ROWS = LargeTodayOverlayPolicy.MIN_ROWS
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
        val shared =
            LargeTodayOverlayPolicy.resolve(
                profile = LargeTodayOverlayPolicy.Profile.ANDROID_WIDGET,
                availableColumns = launcherColumns,
                rows = launcherRows,
                useGraph = useGraph,
                todayVisible = todayVisible,
            )
        return Decision(
            enabled = shared.enabled,
            displayColumns = shared.displayColumns,
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
