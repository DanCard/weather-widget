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
        return HourlyBackfillDecision(false, "organic_backfill_active")
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
    lat: Double,
    lon: Double,
) {
    if (!repositoryPresent) return

    // Both callers resolve [lat]/[lon] from their data list with a `?: DEFAULT_LAT/LON` fallback
    // (TemperatureStateResolver from hourlyForecasts, DailyViewHandler from weatherList). When that
    // list is empty — a ghost/unconfigured widget id, or a render before any fetch — the location
    // collapses to the DEFAULT sentinel (Googleplex, ~0.7 km from a real GPS fix). Fetching NWS
    // observations there fragments them away from where all real data lives, so the Current
    // Observations screen (scoped to the device location) shows "none found". Real GPS data is never
    // exactly the constant (see the same sentinel test in WeatherWidgetWorker), so an exact match
    // means "no location to anchor this fetch to" → skip rather than fetch at a guess.
    if (lat == WeatherWidgetWorker.DEFAULT_LAT && lon == WeatherWidgetWorker.DEFAULT_LON) {
        database.appLogDao().log(
            "OBS_HOURLY_BACKFILL_SKIP",
            "widget=$appWidgetId source=${displaySource.id} reason=unanchored_default_location",
            "INFO",
        )
        return
    }

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

    // Fetch under the widget's resolved location — NOT observations.firstOrNull()?.location.
    // The decisive trigger here is reason=no_nws_observations, in which case the proximity-box
    // observation list is either empty (firstOrNull null) or holds only *other* sources' rows
    // fetched under a neighbouring coordinate. The old DEFAULT_LAT/LON fallback then wrote the
    // NWS backfill under Google HQ (37.422/-122.0841), ~0.7 km from a real GPS fix (37.4168) —
    // a permanent LocationMatch fragment that selectNearestSite later drops, so the Current
    // Observations screen showed "No recent observations found for NWS". See [[snapshot_paths_must_select_a_site]].
    // Jittered short delay: avoids landing in the first-second startup scrum (see
    // StartupFetchPolicy) without meaningfully slowing the interactive missing-hourly-data banner
    // flow, which already tolerates a several-second wait.
    val delayMs = com.weatherwidget.widget.StartupFetchPolicy.historyRepairDelayMs()
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
            .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        WeatherWidgetProvider.WORK_NAME_OBSERVATION_BACKFILL,
        ExistingWorkPolicy.KEEP,
        request,
    )
    stateManager.markMissingActualsRefreshRequested(appWidgetId, HOURLY_BACKFILL_SOURCE_KEY)
    database.appLogDao().log(
        "OBS_HOURLY_BACKFILL_REQ",
        "widget=$appWidgetId source=${displaySource.id} reason=${decision.reason} graphStart=$graphStart graphEnd=$graphEnd delayMs=$delayMs",
        "INFO",
    )
}
