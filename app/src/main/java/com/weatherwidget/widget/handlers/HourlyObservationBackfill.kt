package com.weatherwidget.widget.handlers

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.TodayActualsCoverage
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.WidgetWorkScheduler
import java.time.LocalDateTime
import java.time.ZoneId

private const val HOURLY_BACKFILL_COOLDOWN_MS = 30 * 60 * 1000L

/** Half of one [LocationMatch.WRITE_QUANTIZE_DECIMALS] step — see [backfillSiteMismatchReason]. */
private val QUANTIZE_SLACK_DEG = 0.5 * Math.pow(10.0, -LocationMatch.WRITE_QUANTIZE_DECIMALS.toDouble())

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
 * ([WidgetStateManager.getStoredWidgetLocation], null when the widget has no configured location —
 * deliberately the authoritative read, never the inferring one).
 *
 * Unanchored must SKIP (Fix C) — better no observations than observations filed under a coordinate
 * nobody chose. There used to be a second way to be unanchored: a location [LocationMatch.sameSite]
 * the hard Googleplex default. That guard is gone because the default itself is gone; "no location"
 * is now represented by the absence of coordinates, which the null check above already covers.
 *
 * The lesson from that guard is worth keeping: its first version compared the raw constant with `==`,
 * but the coordinate flowing through had been 3-dp quantized (−122.0841 → −122.084), so `==` silently
 * missed and the fetch proceeded at HQ. Any surviving comparison against those coordinates — there is
 * exactly one, in [com.weatherwidget.widget.LegacyDefaultLocationMigration] — must use `sameSite`.
 *
 * Anchored coordinates are quantized to the shared write-key grid so the fetched rows land on the
 * same key every source uses (Fix A alignment).
 */
@androidx.annotation.VisibleForTesting
internal fun resolveBackfillLocation(widgetLocation: Pair<Double, Double>?): BackfillLocation {
    if (widgetLocation == null) return BackfillLocation.Unanchored("unanchored_no_widget_location")
    val (lat, lon) = widgetLocation
    if (!lat.isFinite() || !lon.isFinite()) {
        return BackfillLocation.Unanchored("unanchored_non_finite_location")
    }
    return BackfillLocation.Anchored(LocationMatch.quantize(lat), LocationMatch.quantize(lon))
}

/**
 * Non-null when the site we are about to fetch is NOT the site the observations being judged were
 * loaded from — i.e. when the coverage decision cannot say anything about the fetch site.
 *
 * [evaluateHourlyBackfillNeed] reads the list the *renderer* loaded (scoped to the render location),
 * while the fetch goes to the widget's stored location. When those disagree the two halves talk past
 * each other: the rows land at site B, the check keeps reading site A, `no_nws_observations` never
 * becomes false, and the request repeats on every paint. That loop is what filed 4,744 observation
 * rows for a test fixture's location on the emulator — rows the renderer will never read, because
 * its own query is boxed around a site 1,500 miles away.
 *
 * The box is [LocationMatch.TOLERANCE_DEG] because that is exactly the box the observation query
 * used (`ROOM_WHERE`): inside it, the loaded rows *could* contain the fetch site's observations, so
 * the decision is meaningful; outside it, they provably cannot.
 *
 * Skipping is safe when the disagreement is legitimate — say the user just re-pinned the location
 * and this paint still holds the old rows. The next paint loads at the new site, the two agree, and
 * the backfill proceeds one cycle later.
 */
@androidx.annotation.VisibleForTesting
internal fun backfillSiteMismatchReason(
    fetch: BackfillLocation.Anchored,
    observationsLat: Double,
    observationsLon: Double,
): String? {
    // Non-finite means the caller could not say where it loaded from. Treat that as a mismatch
    // rather than assuming agreement: a wrong fetch here is a permanent mis-keyed row.
    if (!observationsLat.isFinite() || !observationsLon.isFinite()) {
        return "location_mismatch_obs_location_unknown"
    }
    // Half a write-quantum of slack. `fetch` is quantized to WRITE_QUANTIZE_DECIMALS while the
    // observation coordinate is raw, so a genuine boundary case can land a rounding step outside the
    // box (37.417 + 0.1 quantizes to 37.517, a delta of 0.1000000000000014) and be rejected for a
    // difference far below the precision either value carries. The slack errs toward "same site",
    // which is the safe direction: this guard exists to catch another *town*, not another metre.
    val boundary = LocationMatch.TOLERANCE_DEG + QUANTIZE_SLACK_DEG
    val latDelta = kotlin.math.abs(fetch.lat - observationsLat)
    val lonDelta = kotlin.math.abs(fetch.lon - observationsLon)
    if (latDelta <= boundary && lonDelta <= boundary) {
        return null
    }
    return "location_mismatch fetch=${fetch.lat},${fetch.lon} " +
        "observations=$observationsLat,$observationsLon"
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

    // Gap density is blind to a truncated START. A window that begins at noon is perfectly dense
    // over the half-day it covers, so every gap check above passes while today's observed low is
    // simply absent — and the minimum that survives is the earliest afternoon reading wearing the
    // day-low label. Samsung 2026-08-22: `coverage_ok latest_gap_min=19 max_gap_min=10` logged
    // repeatedly while the today column showed 66.52° (noon) instead of 57.03°.
    //
    // Only meaningful when the loaded window actually reaches back to midnight; while the user
    // browses history the rows for today may never have been loaded, and their absence says nothing
    // about coverage.
    val zone = ZoneId.systemDefault()
    val todayDate = now.toLocalDate()
    val startOfToday = todayDate.atStartOfDay()
    val windowReachesDayStart = !graphStart.isAfter(startOfToday) && graphEnd.isAfter(startOfToday)
    val todayStartUncovered = windowReachesDayStart &&
        TodayActualsCoverage.dayStartUncovered(sortedTimestamps, todayDate, zone)
    val firstTodayMs = sortedTimestamps.firstOrNull {
        it >= startOfToday.atZone(zone).toInstant().toEpochMilli()
    }

    return when {
        singletonStations.isNotEmpty() ->
            HourlyBackfillDecision(true, "singleton_stations=${singletonStations.sorted().joinToString(",")}")
        latestGapMin > 45L ->
            HourlyBackfillDecision(true, "latest_gap_min=$latestGapMin")
        maxGapMin > 75L ->
            HourlyBackfillDecision(true, "max_gap_min=$maxGapMin")
        todayStartUncovered ->
            HourlyBackfillDecision(
                true,
                "day_start_uncovered firstToday=" +
                    (firstTodayMs?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalTime().toString() } ?: "none"),
            )
        else ->
            metarCloudGapReason(sourceObservations)?.let { HourlyBackfillDecision(true, it) }
                ?: HourlyBackfillDecision(false, "coverage_ok latest_gap_min=$latestGapMin max_gap_min=$maxGapMin")
    }
}

/**
 * Detects a broken METAR actual-cloud series under otherwise-healthy temperature coverage.
 *
 * Sky condition rides the SAME `/observations` payload as temperature, so a temperature-only
 * "coverage_ok" cannot see it: rows stored before cloud parsing existed carry `cloudCoverLow=NULL`
 * forever, and no later event re-parses them. When fewer than half of the hour-buckets an official
 * station reports into carry any cloud value, the series is broken (pre-feature rows, a dead feed)
 * and the existing 72h re-fetch repairs it — REPLACE re-parses the same bytes with `cloudLayers`,
 * no new HTTP calls. The ordinary cooldown bounds how often a genuinely cloudless location retries.
 *
 * OFFICIAL-only basis: PERSONAL stations have no ceilometer and report `cloudLayers: []` on every
 * report, so they can never satisfy the check and must not keep it firing. Buckets use the blender's
 * round-to-nearest-hour rule.
 *
 * Web-fallback rows ARE counted. They were excluded until 2026-08-21 on the stated grounds that
 * Synoptic rows are "temperature-only by policy" — untrue: Synoptic returns the raw METAR and the
 * parser simply ignored it, so the exclusion was resting on a fact that was never checked.
 *
 * Note what counting them does and does not buy. The measure is **bucket-level**: a bucket counts as
 * covered when *any* official station reported cloud in that hour, so one healthy station masks every
 * other. Measured on the emulator right after this change, admitting KNUQ's and KPAO's rows moved the
 * ratio from 42/66 to 44/72 — still healthy — because KSJC covers nearly every bucket by itself. This
 * check therefore detects a broken *series*, never a single station that has stopped contributing;
 * that gap is real and is not what this function is for.
 */
@androidx.annotation.VisibleForTesting
internal fun metarCloudGapReason(sourceObservations: List<ObservationEntity>): String? {
    val officialRows = sourceObservations
        .filter { it.stationType == "OFFICIAL" && !it.qcFailed }
    if (officialRows.isEmpty()) return null
    // The blender's shared round-to-nearest-hour rule — the buckets this check counts must be the
    // same buckets the blend emits, or "cloud sparse here" says nothing about the curve.
    fun bucketOf(ts: Long) = com.weatherwidget.shared.observations.CloudHourBucket.indexOf(ts)
    val officialBuckets = officialRows.map { bucketOf(it.timestamp) }.distinct().size
    val cloudBuckets = officialRows
        .filter { (it.cloudCoverLow ?: it.cloudCover) != null }
        .map { bucketOf(it.timestamp) }
        .distinct()
        .size
    // ~25-30% of individual reports omit sky condition, but with several reports per bucket an
    // empty bucket is a few percent; half the official buckets empty means the series is broken.
    return if (cloudBuckets * 2 < officialBuckets) {
        "metar_cloud_sparse cloudBuckets=$cloudBuckets officialBuckets=$officialBuckets"
    } else {
        null
    }
}

/**
 * Cooldown key for the hourly-observation backfill, scoped to **source and site**.
 *
 * The site component is why this is not just the source id. A move is exactly when a backfill is
 * most needed — the new site has no history at all — and it was exactly when the cooldown blocked
 * it: a heal at the old site suppressed the new site's for 30 minutes, because both hashed to
 * `NWS_HOURLY_HISTORY`. Samsung 2026-08-22 is the case in point; a GPS excursion created a site
 * whose day began at noon, and nothing was allowed to repair it.
 *
 * Coordinates are quantized to [LocationMatch.WRITE_QUANTIZE_DECIMALS] (~110 m), matching how the
 * coordinate-keyed tables are written. That is deliberate in both directions: GPS jitter around one
 * spot keeps sharing a bucket, so the cooldown still bounds retries and a wobbling fix cannot
 * hammer the API; a genuinely different site gets its own bucket and its own chance to heal.
 */
internal fun hourlyBackfillSourceKey(
    displaySource: WeatherSource,
    lat: Double,
    lon: Double,
): String =
    "${displaySource.id}_HOURLY_HISTORY_${LocationMatch.quantize(lat)}_${LocationMatch.quantize(lon)}"

/**
 * Cheap pure-read pre-check for callers that would otherwise load a large observation window just
 * to feed [maybeEnqueueHourlyObservationBackfill] (the CLOUD view probe). When the shared cooldown
 * is active the full evaluation could only ever log a cooldown SKIP, so the caller can skip its
 * expensive DB read too.
 */
internal suspend fun hourlyBackfillCoolingDown(
    stateManager: WidgetStateManager,
    appWidgetId: Int,
    displaySource: WeatherSource,
    lat: Double,
    lon: Double,
): Boolean = !stateManager.shouldRefreshMissingActuals(
    appWidgetId,
    hourlyBackfillSourceKey(displaySource, lat, lon),
    HOURLY_BACKFILL_COOLDOWN_MS,
)

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
    /**
     * Where [observations] were loaded from. Required so the coverage decision and the fetch cannot
     * be about two different places — see [backfillSiteMismatchReason].
     */
    observationsLat: Double,
    observationsLon: Double,
) {
    if (!repositoryPresent) return

    // Resolve WHERE to fetch from the widget's authoritative location, not from the (possibly empty,
    // possibly DEFAULT) coordinate the caller rendered with. resolveBackfillLocation SKIPs an
    // unanchored widget instead of fetching NWS obs at Googleplex — the fragmentation this whole
    // path exists to prevent. See plan 260721.
    // `getStoredWidgetLocation`: an inferred coordinate is exactly what must not anchor an
    // observation write. A mis-keyed row is a permanent LocationMatch fragment.
    val fetchLocation = when (val resolved = resolveBackfillLocation(stateManager.getStoredWidgetLocation(appWidgetId))) {
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

    // Before trusting the coverage decision, check it is even about this site.
    backfillSiteMismatchReason(fetchLocation, observationsLat, observationsLon)?.let { reason ->
        database.appLogDao().log(
            "OBS_HOURLY_BACKFILL_SKIP",
            "widget=$appWidgetId source=${displaySource.id} reason=$reason",
            "WARN",
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

    val sourceKey = hourlyBackfillSourceKey(displaySource, lat, lon)
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
    // fetched under a neighbouring coordinate. The old hard-default fallback then wrote the
    // NWS backfill under Google HQ, ~0.7 km from a real GPS fix (37.4168) —
    // a permanent LocationMatch fragment that selectNearestSite later drops, so the Current
    // Observations screen showed "No recent observations found for NWS". See [[snapshot_paths_must_select_a_site]].
    // Jittered short delay: avoids landing in the first-second startup scrum (see
    // StartupFetchPolicy) without meaningfully slowing the interactive missing-hourly-data banner
    // flow, which already tolerates a several-second wait.
    val backfillReason =
        "temperature_graph_sparse_history widget=$appWidgetId reason=${decision.reason}"
    val request = WidgetWorkScheduler.enqueueRequiredObservationBackfill(
        context = context,
        latitude = lat,
        longitude = lon,
        lookbackHours = WeatherWidgetWorker.DEFAULT_OBSERVATION_BACKFILL_HOURS,
        reason = backfillReason,
        initialDelayMs = delayMs,
    )
    stateManager.markMissingActualsRefreshRequested(appWidgetId, sourceKey)
    database.appLogDao().log(
        "OBS_HOURLY_BACKFILL_REQ",
        "widget=$appWidgetId source=${displaySource.id} reason=${decision.reason} " +
            "graphStart=$graphStart graphEnd=$graphEnd delayMs=$delayMs " +
            "policy=append_or_replace requestId=${request.id}",
        "INFO",
    )
}
