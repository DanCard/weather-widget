package com.weatherwidget.widget.handlers

import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.DailyActualMap
import java.time.LocalDate

@VisibleForTesting
internal data class MissingDataRefreshDecision(
    val refreshType: String,
    val forceRefresh: Boolean,
    val reason: String,
) {
    val cooldownMs: Long get() = MISSING_DATA_REFRESH_COOLDOWN_MS
    val logTag: String get() = if (forceRefresh) "MISSING_ACTUALS_FETCH" else "MISSING_TODAY_SNAPSHOT_FETCH"
}

private const val MISSING_DATA_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L

@VisibleForTesting
internal fun computeMissingDataRefreshes(
    today: LocalDate,
    displaySource: WeatherSource,
    dailyActuals: DailyActualMap,
    visibleDates: Set<LocalDate> = emptySet(),
    todayHasSnapshot: Boolean = true,
    todayHasForecast: Boolean = true,
): List<MissingDataRefreshDecision> {
    val decisions = mutableListOf<MissingDataRefreshDecision>()

    if (dailyActuals[today] == null) {
        decisions.add(
            MissingDataRefreshDecision(
                refreshType = "actuals_today",
                forceRefresh = true,
                reason = "missing_actuals_${displaySource.id}_today",
            ),
        )
    }

    if (todayHasForecast && !todayHasSnapshot) {
        decisions.add(
            MissingDataRefreshDecision(
                refreshType = "today_snapshot",
                forceRefresh = false,
                reason = "missing_today_snapshot_${displaySource.id}",
            ),
        )
    }

    if (visibleDates.any { it.isBefore(today) && dailyActuals[it] == null }) {
        decisions.add(
            MissingDataRefreshDecision(
                refreshType = "actuals_history",
                forceRefresh = true,
                reason = "missing_actuals_${displaySource.id}_history",
            ),
        )
    }

    return decisions
}
