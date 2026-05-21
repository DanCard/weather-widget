package com.weatherwidget.widget.handlers

import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.ObservationResolver
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
    dailyActuals: Map<LocalDate, ObservationResolver.DailyActual>,
    displayDays: List<DailyForecastGraphRenderer.DayData> = emptyList(),
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

    val missingTodaySnapshot = displayDays.firstOrNull { day ->
        day.isToday &&
            day.dashedLineHigh != null &&
            day.dashedLineLow != null &&
            day.snapshotHigh == null &&
            day.snapshotLow == null
    }
    if (missingTodaySnapshot != null) {
        decisions.add(
            MissingDataRefreshDecision(
                refreshType = "today_snapshot",
                forceRefresh = false,
                reason = "missing_today_snapshot_${displaySource.id}",
            ),
        )
    }

    val missingVisiblePastActuals = displayDays.firstOrNull { day ->
        day.isPast &&
            dailyActuals[day.date] == null &&
            day.dashedLineHigh != null &&
            day.dashedLineLow != null
    }
    if (missingVisiblePastActuals != null) {
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