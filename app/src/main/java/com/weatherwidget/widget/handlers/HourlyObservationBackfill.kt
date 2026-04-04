package com.weatherwidget.widget.handlers

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDateTime
import java.time.ZoneId

private const val HOURLY_BACKFILL_COOLDOWN_MS = 30 * 60 * 1000L
private const val HOURLY_BACKFILL_SOURCE_KEY = "NWS_HOURLY_HISTORY"

@androidx.annotation.VisibleForTesting
internal data class HourlyBackfillDecision(
    val shouldRequest: Boolean,
    val reason: String,
)

@androidx.annotation.VisibleForTesting
internal fun evaluateHourlyBackfillNeed(
    displaySource: WeatherSource,
    graphStart: LocalDateTime,
    graphEnd: LocalDateTime,
    observations: List<ObservationEntity>,
    now: LocalDateTime = LocalDateTime.now(),
): HourlyBackfillDecision {
    if (displaySource != WeatherSource.NWS) {
        return HourlyBackfillDecision(false, "non_nws_source")
    }

    val sourceObservations = observations.filter { matchesObservationSource(it, displaySource) }
    if (sourceObservations.isEmpty()) {
        return HourlyBackfillDecision(true, "no_nws_observations")
    }

    val sourceWindowEnd = minOf(graphEnd, now)
    val sourceWindowEndMs = sourceWindowEnd.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val sortedTimestamps = sourceObservations.map { it.timestamp }.sorted()
    val latestGapMin = ((sourceWindowEndMs - sortedTimestamps.last()).coerceAtLeast(0L) / 60_000L)
    val maxGapMin = sortedTimestamps.zipWithNext { a, b -> (b - a) / 60_000L }.maxOrNull() ?: 0L
    val singletonStations =
        sourceObservations.groupBy { it.stationId }
            .filterValues { rows -> rows.size <= 1 }
            .keys

    return when {
        singletonStations.isNotEmpty() ->
            HourlyBackfillDecision(true, "singleton_stations=${singletonStations.sorted().joinToString(",")}")
        latestGapMin > 45L ->
            HourlyBackfillDecision(true, "latest_gap_min=$latestGapMin")
        maxGapMin > 75L ->
            HourlyBackfillDecision(true, "max_gap_min=$maxGapMin")
        else ->
            HourlyBackfillDecision(false, "coverage_ok latest_gap_min=$latestGapMin max_gap_min=$maxGapMin")
    }
}

internal suspend fun maybeEnqueueHourlyObservationBackfill(
    context: Context,
    database: com.weatherwidget.data.local.WeatherDatabase,
    stateManager: WidgetStateManager,
    appWidgetId: Int,
    displaySource: WeatherSource,
    graphStart: LocalDateTime,
    graphEnd: LocalDateTime,
    observations: List<ObservationEntity>,
    repositoryPresent: Boolean,
) {
    if (!repositoryPresent) return

    val decision = evaluateHourlyBackfillNeed(displaySource, graphStart, graphEnd, observations)
    if (!decision.shouldRequest) {
        database.appLogDao().log(
            "OBS_HOURLY_BACKFILL_SKIP",
            "widget=$appWidgetId source=${displaySource.id} reason=${decision.reason}",
            "INFO",
        )
        return
    }

    if (!stateManager.shouldRefreshMissingActuals(appWidgetId, HOURLY_BACKFILL_SOURCE_KEY, HOURLY_BACKFILL_COOLDOWN_MS)) {
        database.appLogDao().log(
            "OBS_HOURLY_BACKFILL_SKIP",
            "widget=$appWidgetId source=${displaySource.id} reason=cooldown ${decision.reason}",
            "INFO",
        )
        return
    }

    val lat = observations.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
    val lon = observations.firstOrNull()?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON
    val request =
        OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setInputData(
                androidx.work.Data.Builder()
                    .putBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ONLY, true)
                    .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LAT, lat)
                    .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LON, lon)
                    .putLong(
                        WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_HOURS,
                        WeatherWidgetWorker.DEFAULT_OBSERVATION_BACKFILL_HOURS,
                    )
                    .putString(
                        WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_REASON,
                        "temperature_graph_sparse_history widget=$appWidgetId reason=${decision.reason}",
                    )
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        WeatherWidgetProvider.WORK_NAME_OBSERVATION_BACKFILL,
        ExistingWorkPolicy.KEEP,
        request,
    )
    stateManager.markMissingActualsRefreshRequested(appWidgetId, HOURLY_BACKFILL_SOURCE_KEY)
    database.appLogDao().log(
        "OBS_HOURLY_BACKFILL_REQ",
        "widget=$appWidgetId source=${displaySource.id} reason=${decision.reason} graphStart=$graphStart graphEnd=$graphEnd",
        "INFO",
    )
}
