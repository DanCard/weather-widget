package com.weatherwidget.widget.handlers

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.WidgetWorkScheduler
import java.time.LocalDateTime
import java.time.ZoneId

private const val HOURLY_BACKFILL_COOLDOWN_MS = 30 * 60 * 1000L

@androidx.annotation.VisibleForTesting
internal data class HourlyBackfillDecision(
    val shouldRequest: Boolean,
    val reason: String,
)

/**
 * Where an observation backfill should fetch, resolved from the widget's authoritative location —
 * never from the data being backfilled (that idiom, `list.firstOrNull()?.location ?: DEFAULT`,
 * silently wrote NWS obs at Googleplex whenever the box query was empty, fragmenting them ~0.7 km
 * from the real fix; see plan 260721 / [[hourly_backfill_default_location_fragment]]).
 */
@androidx.annotation.VisibleForTesting
internal sealed interface BackfillLocation {
    /** A real, quantized site to fetch under. */
    data class Anchored(val lat: Double, val lon: Double) : BackfillLocation
    /** No trustworthy location — skip the fetch rather than guess. [reason] is logged. */
    data class Unanchored(val reason: String) : BackfillLocation
}

/**
 * Pure resolution of the fetch location from the widget's stored location
 * ([WidgetStateManager.getWidgetLocation], null when the widget has no configured/POI location).
 *
 * Two ways to be unanchored, both of which must SKIP (Fix C): (1) no location at all; (2) a location
 * that is [LocationMatch.sameSite] the hard default (Googleplex). The old guard compared the raw
 * constant with `==`, but the coordinate flowing through had been 3-dp quantized (−122.0841 →
 * −122.084), so `==` silently missed it and the fetch proceeded at HQ. `sameSite` is quantization-safe.
 *
 * Anchored coordinates are quantized to the shared write-key grid so the fetched rows land on the
 * same key every source uses (Fix A alignment).
 */
@androidx.annotation.VisibleForTesting
internal fun resolveBackfillLocation(widgetLocation: Pair<Double, Double>?): BackfillLocation {
    if (widgetLocation == null) return BackfillLocation.Unanchored("unanchored_no_widget_location")
    val (lat, lon) = widgetLocation
    if (LocationMatch.sameSite(lat, lon, WeatherWidgetWorker.DEFAULT_LAT, WeatherWidgetWorker.DEFAULT_LON)) {
        return BackfillLocation.Unanchored("unanchored_default_location")
    }
    return BackfillLocation.Anchored(LocationMatch.quantize(lat), LocationMatch.quantize(lon))
}

@androidx.annotation.VisibleForTesting
internal fun evaluateHourlyBackfillNeed(
    displaySource: WeatherSource,
    graphStart: LocalDateTime,
    graphEnd: LocalDateTime,
    observations: List<ObservationEntity>,
    now: LocalDateTime = LocalDateTime.now(),
): HourlyBackfillDecision {
    if (displaySource == WeatherSource.WEATHER_API) {
        val yesterday = now.toLocalDate().minusDays(1)
        if (graphStart.toLocalDate().isAfter(yesterday)) {
            return HourlyBackfillDecision(false, "weatherapi_history_not_visible")
        }
        val zone = ZoneId.systemDefault()
        val distinctHours =
            observations.asSequence()
                .filter { matchesObservationSource(it, displaySource) }
                .filter {
                    java.time.Instant.ofEpochMilli(it.timestamp)
                        .atZone(zone)
                        .toLocalDate() == yesterday
                }
                .map { it.timestamp / 3_600_000L }
                .distinct()
                .count()
        return if (
            distinctHours <
            com.weatherwidget.shared.history.ProviderHistoryPolicy.COMPLETE_DAY_MIN_DISTINCT_HOURS
        ) {
            HourlyBackfillDecision(
                true,
                "weatherapi_history_sparse hours=$distinctHours date=$yesterday",
            )
        } else {
            HourlyBackfillDecision(
                false,
                "weatherapi_history_covered hours=$distinctHours date=$yesterday",
            )
        }
    }

    if (displaySource != WeatherSource.NWS) {
        val reason =
            when (displaySource) {
                WeatherSource.OPEN_METEO,
                WeatherSource.SILURIAN,
                WeatherSource.TOMORROW_IO -> "provider_history_in_forecast"
                else -> "provider_history_unsupported"
            }
        return HourlyBackfillDecision(false, reason)
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

    // Resolve WHERE to fetch from the widget's authoritative location, not from the (possibly empty,
    // possibly DEFAULT) coordinate the caller rendered with. resolveBackfillLocation SKIPs an
    // unanchored widget instead of fetching NWS obs at Googleplex — the fragmentation this whole
    // path exists to prevent. See plan 260721.
    val fetchLocation = when (val resolved = resolveBackfillLocation(stateManager.getWidgetLocation(appWidgetId))) {
        is BackfillLocation.Unanchored -> {
            database.appLogDao().log(
                "OBS_HOURLY_BACKFILL_SKIP",
                "widget=$appWidgetId source=${displaySource.id} reason=${resolved.reason}",
                "INFO",
            )
            return
        }
        is BackfillLocation.Anchored -> resolved
    }
    val lat = fetchLocation.lat
    val lon = fetchLocation.lon

    val decision = evaluateHourlyBackfillNeed(displaySource, graphStart, graphEnd, observations)
    if (!decision.shouldRequest) {
        database.appLogDao().log(
            "OBS_HOURLY_BACKFILL_SKIP",
            "widget=$appWidgetId source=${displaySource.id} reason=${decision.reason}",
            "INFO",
        )
        return
    }

    val sourceKey = "${displaySource.id}_HOURLY_HISTORY"
    if (!stateManager.shouldRefreshMissingActuals(appWidgetId, sourceKey, HOURLY_BACKFILL_COOLDOWN_MS)) {
        database.appLogDao().log(
            "OBS_HOURLY_BACKFILL_SKIP",
            "widget=$appWidgetId source=${displaySource.id} reason=cooldown ${decision.reason}",
            "INFO",
        )
        return
    }

    val delayMs = com.weatherwidget.widget.StartupFetchPolicy.historyRepairDelayMs()
    if (displaySource == WeatherSource.WEATHER_API) {
        RefreshScheduler.enqueueForcedRefresh(
            context = context,
            reason = "weatherapi_history_sparse widget=$appWidgetId",
            policy = ExistingWorkPolicy.KEEP,
            initialDelayMs = delayMs,
            targetSourceId = WeatherSource.WEATHER_API.id,
        )
        stateManager.markMissingActualsRefreshRequested(appWidgetId, sourceKey)
        database.appLogDao().log(
            "OBS_HOURLY_BACKFILL_REQ",
            "widget=$appWidgetId source=${displaySource.id} mode=provider_history " +
                "reason=${decision.reason} graphStart=$graphStart graphEnd=$graphEnd delayMs=$delayMs",
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
        WidgetWorkScheduler.WORK_NAME_OBSERVATION_BACKFILL,
        ExistingWorkPolicy.KEEP,
        request,
    )
    stateManager.markMissingActualsRefreshRequested(appWidgetId, sourceKey)
    database.appLogDao().log(
        "OBS_HOURLY_BACKFILL_REQ",
        "widget=$appWidgetId source=${displaySource.id} reason=${decision.reason} graphStart=$graphStart graphEnd=$graphEnd delayMs=$delayMs",
        "INFO",
    )
}
