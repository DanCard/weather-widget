package com.weatherwidget.data.repository

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.SynopticApi
import com.weatherwidget.data.remote.shouldTouchObservationFetchedAt
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.shared.observations.ObservationFallbackPolicy
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.shared.util.SpatialInterpolator
import java.time.Instant
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.WidgetConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ObservationRepository"

// Local hour by which a finished (past) day must have at least one NWS observation for its
// cached daily high to be trustworthy — the daily high typically lands in the early afternoon.
private const val DAYTIME_COVERAGE_HOUR = 14

/**
 * Returns true when [date] is a *past* day whose NWS observations never reach the afternoon
 * ([daytimeHour]+), meaning the warm part of the day went unrecorded (e.g. the device was off)
 * and the cached daily high/low is likely wrong, so the day should be re-fetched. Days with no
 * observations at all are excluded here — those are caught by the daily_history row-presence
 * check — as are today and future days, whose coverage is legitimately still incomplete.
 */
@VisibleForTesting
internal fun pastDayLacksAfternoonCoverage(
    obsTimestampsMs: List<Long>,
    date: LocalDate,
    zone: ZoneId,
    today: LocalDate,
    daytimeHour: Int = DAYTIME_COVERAGE_HOUR,
): Boolean {
    if (!date.isBefore(today)) return false
    if (obsTimestampsMs.isEmpty()) return false
    val coversAfternoon = obsTimestampsMs.any { ms ->
        java.time.Instant.ofEpochMilli(ms).atZone(zone).hour >= daytimeHour
    }
    return !coversAfternoon
}

@Singleton
class ObservationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val observationDao: ObservationDao,
    private val dailyHistoryDao: DailyHistoryDao,
    private val appLogDao: AppLogDao,
    private val nwsApi: NwsApi,
    private val hourlyForecastDao: HourlyForecastDao,
    private val synopticApi: SynopticApi? = null,
) {
    internal data class RecentBackfillResult(
        val stationsTried: Int,
        val rowsFetched: Int,
        val affectedDates: Set<LocalDate>,
    )

    private val prefs by lazy { com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, "weather_prefs") }

    // App-wide personal-station discount, read fresh so a Settings change takes effect on the next
    // recompute without restarting. Lives in WidgetStateManager's prefs (not this class's "weather_prefs").
    private fun personalStationWeight(): Double =
        com.weatherwidget.widget.WidgetStateManager(context).getPersonalStationWeight()

    /** Epoch millis of an NWS observation; null when absent or unparseable (treated as stale). */
    private fun NwsApi.Observation?.observedAtMillis(): Long? =
        this?.let { runCatching { OffsetDateTime.parse(it.timestamp).toInstant().toEpochMilli() }.getOrNull() }

    companion object {
        private const val MAX_RETRIES = 5
    }

    internal suspend fun fetchNwsCurrent(latitude: Double, longitude: Double): CurrentReadingPayload? = coroutineScope {
        val gridPoint = nwsApi.getGridPoint(latitude, longitude)
        val stations = getSortedObservationStations(gridPoint.observationStationsUrl ?: "")
        if (stations.isEmpty()) return@coroutineScope null

        val stationsToFetch = stations.take(MAX_RETRIES)
        val closestStation = stationsToFetch.first()
        val otherStations = stationsToFetch.drop(1)
        val fetchStartMs = System.currentTimeMillis()

        val closestDeferred = async {
            val retryDelaysMs = listOf(10_000L, 30_000L)
            var entity = fetchStationObservation(closestStation, latitude, longitude, attempt = 0)
            for ((index, delayMs) in retryDelaysMs.withIndex()) {
                if (entity != null) break
                delay(delayMs)
                entity = fetchStationObservation(closestStation, latitude, longitude, attempt = index + 1)
            }
            entity
        }

        val otherDeferreds = otherStations.map { stationInfo ->
            async { fetchStationObservation(stationInfo, latitude, longitude) }
        }

        val successfulEntities = (listOf(closestDeferred) + otherDeferreds).mapNotNull { it.await() }
        val totalFetchDurationMs = System.currentTimeMillis() - fetchStartMs

        if (successfulEntities.isEmpty()) {
            appLogDao.log("NWS_FETCH_FAIL_ALL", "stationsTried=${stationsToFetch.size} totalMs=$totalFetchDurationMs", "WARN")
            return@coroutineScope null
        }

        val blendedTemp = SpatialInterpolator.interpolateIDW(latitude, longitude, successfulEntities.map { it.toReading() })
            ?: return@coroutineScope null

        val closest = successfulEntities.minBy { it.distanceKm }

        val stationSummary = successfulEntities.joinToString { "${it.stationId}(${it.distanceKm}km)" }
        appLogDao.log("NWS_IDW", "blended=${blendedTemp}°F from ${successfulEntities.size} stations: $stationSummary totalMs=$totalFetchDurationMs")
        Log.d(TAG, "NWS IDW blend: $blendedTemp°F from $stationSummary totalMs=$totalFetchDurationMs")

        CurrentReadingPayload(
            WeatherSource.NWS,
            blendedTemp,
            closest.condition,
            successfulEntities.maxOf { it.timestamp },
        )
    }

    private suspend fun fetchStationObservation(
        stationInfo: NwsApi.StationInfo,
        latitude: Double,
        longitude: Double,
        attempt: Int = 0,
    ): ObservationEntity? {
        val nwsOutcome = nwsApi.getLatestObservationDetailedResult(stationInfo.id)
        val observation = nwsOutcome.valueOrNull()

        val nowMs = System.currentTimeMillis()
        val isStale = ObservationFallbackPolicy.isStale(observation.observedAtMillis(), nowMs)

        var isWeb = false
        var synopticOutcome: FetchOutcome<List<NwsApi.Observation>>? = null
        val finalObservation = if (isStale && synopticApi != null) {
            val fallbackReason = when (nwsOutcome) {
                is FetchOutcome.Success -> "stale"
                is FetchOutcome.NoData -> "no_valid_data"
                is FetchOutcome.Failed -> "fail"
            }
            // The window must reach back past the station's newest reading, or the fallback asks a
            // silent station what it did in the last hour and learns nothing (KPAO 2026-07-13).
            val windowMinutes = ObservationFallbackPolicy.webFallbackWindowMinutes(
                observation.observedAtMillis(),
                nowMs,
            )
            appLogDao.log(
                "NWS_STATION_SYNOPTIC_FALLBACK",
                "station=${stationInfo.id} reason=$fallbackReason windowMin=$windowMinutes",
                "INFO",
            )
            Log.i(TAG, "Latest NWS observation for ${stationInfo.id} is missing or stale ($fallbackReason). Querying Synoptic fallback (window=${windowMinutes}min)...")
            synopticOutcome = synopticApi.fetchSynopticObservations(stationInfo.id, windowMinutes, stationInfo.name)
            val synopticReadings = synopticOutcome.valueOrNull().orEmpty()
            // QC-flagged readings are stored (marked) so the stations UI can show the failure, but
            // they must never become the usable observation that feeds the blend. ALL of them are
            // written, not just the newest: a reading stored unflagged by an earlier narrow-window
            // fetch is only healed if this pass rewrites its row (insertAll is REPLACE, keyed on
            // stationId+timestamp), and a wide window can surface several flagged readings at once.
            val flagged = synopticReadings.filter { it.qcFailed }
            if (flagged.isNotEmpty()) {
                val flaggedEntities = flagged.map {
                    buildObservationEntity(it, stationInfo, latitude, longitude, isWebFallback = true)
                }
                observationDao.insertAll(flaggedEntities)
                appLogDao.log(
                    "OBS_QC_FLAGGED",
                    "station=${stationInfo.id} count=${flaggedEntities.size} " +
                        "timestamps=${flaggedEntities.joinToString(",") { it.timestamp.toString() }} " +
                        "temps=${flaggedEntities.joinToString(",") { it.temperature.toString() }}",
                    "WARN",
                )
            }
            val synopticLatest = synopticReadings.lastOrNull { !it.qcFailed }
            if (synopticLatest != null) {
                isWeb = true
                synopticLatest
            } else {
                observation
            }
        } else {
            observation
        }

        if (finalObservation == null) {
            if (shouldTouchObservationFetchedAt(nwsOutcome, synopticOutcome)) {
                // The attempt completed and at least one upstream definitively had nothing
                // storable — e.g. a station publishing only null-temperature reports (KNUQ
                // 2026-07-13). Record the attempt on the newest stored row so the observations
                // UI shows a fresh "Fetched" against an old "Reported" instead of both frozen.
                observationDao.touchLatestFetchedAt(stationInfo.id, System.currentTimeMillis())
                appLogDao.log(
                    "OBS_ATTEMPT_TOUCH",
                    "station=${stationInfo.id} reason=no_valid_observation attempt=$attempt",
                    "INFO",
                )
            } else {
                // Every upstream failed outright — we learned nothing about the station, so its
                // fetchedAt stays frozen and the failure is reported instead.
                val nwsReason = (nwsOutcome as? FetchOutcome.Failed)?.reason ?: "unknown"
                val synopticReason = (synopticOutcome as? FetchOutcome.Failed)?.reason ?: "not_tried"
                appLogDao.log(
                    "NWS_STATION_FAIL",
                    "station=${stationInfo.id} attempt=$attempt nws=$nwsReason synoptic=$synopticReason",
                    "WARN",
                )
                Log.w(TAG, "NWS station ${stationInfo.id} attempt $attempt failed: nws=$nwsReason synoptic=$synopticReason")
            }
            return null
        }

        if (attempt > 0) {
            appLogDao.log("NWS_STATION_RETRY_OK", "station=${stationInfo.id} attempt=$attempt", "INFO")
            Log.d(TAG, "NWS station ${stationInfo.id} succeeded on retry attempt $attempt")
        }
        val obsEntity = buildObservationEntity(finalObservation, stationInfo, latitude, longitude, isWeb)
        observationDao.insertAll(listOf(obsEntity))
        // The stored reading can be OLDER than the station's newest row (e.g. NWS returning its
        // stale latest while a fresher web-fallback reading is already stored, KPAO 2026-07-13).
        // fetchedAt means "last completed attempt", and the stations list shows the newest row —
        // touch it so "Fetched" reflects this attempt. No-op when the insert IS the newest row.
        observationDao.touchLatestFetchedAt(stationInfo.id, System.currentTimeMillis())
        logCurrentObservationInsert(obsEntity)
        val obsDate = java.time.Instant.ofEpochMilli(obsEntity.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        recomputeDailyExtremesForDay(latitude, longitude, obsDate, emptyList())
        return obsEntity
    }

    private suspend fun logCurrentObservationInsert(obsEntity: ObservationEntity) {
        val nowMs = System.currentTimeMillis()
        appLogDao.log(
            "OBS_CURRENT_INSERT",
            "source=${obsEntity.api} station=${obsEntity.stationId} timestamp=${obsEntity.timestamp} " +
                "fetchedAt=${obsEntity.fetchedAt} temp=${obsEntity.temperature} " +
                "timestampAgeMin=${(nowMs - obsEntity.timestamp) / 60_000L} " +
                "fetchAgeMin=${(nowMs - obsEntity.fetchedAt) / 60_000L}",
            "INFO",
        )
    }

    private suspend fun getSortedObservationStations(stationsUrl: String): List<NwsApi.StationInfo> {
        if (stationsUrl.isEmpty()) return emptyList()

        val stationsKey = "observation_stations_v4_${stationsUrl.hashCode()}"
        val timeKey = "observation_stations_time_v4_${stationsUrl.hashCode()}"
        val cachedStationsString = prefs.getString(stationsKey, null)
        val lastUpdateTimestamp = prefs.getLong(timeKey, 0)

        if (cachedStationsString != null && System.currentTimeMillis() - lastUpdateTimestamp < 86400000) {
            return cachedStationsString.split("|").mapNotNull(NwsApi.Companion::decodeStationInfo)
        }

        val fetchedStations = runCatching { nwsApi.getObservationStations(stationsUrl) }.getOrDefault(emptyList())
        if (fetchedStations.isNotEmpty()) {
            prefs.edit()
                .putString(stationsKey, fetchedStations.joinToString("|", transform = NwsApi.Companion::encodeStationInfo))
                .putLong(timeKey, System.currentTimeMillis())
                .apply()
        }
        return fetchedStations
    }

    internal suspend fun backfillNwsObservationsIfNeeded(latitude: Double, longitude: Double) {
        Log.d(TAG, "backfillNwsObservationsIfNeeded entered for ($latitude, $longitude)")
        val localZone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(localZone)

        val dayMinus2 = now.minusDays(2).toLocalDate()
        val yesterday = now.minusDays(1).toLocalDate()
        val today = now.toLocalDate()
        val currentHour = now.hour
        val dayMinus2Epoch = dayMinus2.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val yesterdayEpoch = yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val todayEpoch = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val requiredDates = buildSet {
            add(dayMinus2Epoch)
            add(yesterdayEpoch)
            if (currentHour >= 2) add(todayEpoch)
        }
        val existingDates =
            dailyHistoryDao.getExtremesInRange(dayMinus2Epoch, todayEpoch, latitude, longitude)
                .filter { it.source == WeatherSource.NWS.id }
                .map { it.date }
                .toSet()
        val missingDates = requiredDates - existingDates
        // A daily_history row can exist yet be wrong when that day's observations don't reach the
        // afternoon (device off, partial coverage). Treat those present-but-incomplete past days as
        // needing a re-fetch too — the row-presence check above cannot see this.
        val incompleteDates = incompletelyCoveredPastDates(existingDates, latitude, longitude, localZone, today)
        val datesToBackfill = missingDates + incompleteDates

        Log.d(TAG, "History check: requiredDates=${requiredDates.map { java.time.LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY) }} existingDates=${existingDates.map { java.time.LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY) }} missingDates=${missingDates.map { java.time.LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY) }} incompleteDates=${incompleteDates.map { java.time.LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY) }} hour=$currentHour")

        if (datesToBackfill.isEmpty()) {
            Log.d(TAG, "Skipping backfill: required NWS daily_history rows exist with adequate coverage")
            return
        }

        Log.i(TAG, "Backfilling NWS daily_history for ${datesToBackfill.map { java.time.LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY) }} (missing or incomplete), fetching last ${WeatherConfig.NWS_BACKFILL_DAYS * 24} hours")
        val gridPoint = runCatching { nwsApi.getGridPoint(latitude, longitude) }.getOrNull()
        if (gridPoint == null) {
            Log.e(TAG, "Failed to get grid point for ($latitude, $longitude)")
            return
        }

        val stations = getSortedObservationStations(gridPoint.observationStationsUrl ?: "")
        Log.d(TAG, "Found ${stations.size} stations to try")
        if (stations.isEmpty()) return

        val startTimeStr = DateTimeFormatter.ISO_INSTANT.format(now.minusDays(WeatherConfig.NWS_BACKFILL_DAYS.toLong()).toInstant())
        val endTimeStr = DateTimeFormatter.ISO_INSTANT.format(now.toInstant())
        val remainingDates = datesToBackfill.toMutableSet()

        val stationsToTry = stations.take(MAX_RETRIES)
        for ((index, stationInfo) in stationsToTry.withIndex()) {
            Log.d(TAG, "Attempting backfill from station ${stationInfo.id}")
            try {
                val observations = try {
                    nwsApi.getObservations(stationInfo.id, startTimeStr, endTimeStr)
                } catch (e: Exception) {
                    emptyList()
                }

                val newestObservationMs = observations.mapNotNull { it.observedAtMillis() }.maxOrNull()

                var isWeb = false
                val useWebFallback = synopticApi != null &&
                    ObservationFallbackPolicy.shouldUseWebFallback(index, newestObservationMs, System.currentTimeMillis())
                val finalObservations = if (useWebFallback) {
                    val fallbackReason = ObservationFallbackPolicy.fallbackReason(observations.size)
                    appLogDao.log("NWS_DAILY_SYNOPTIC_FALLBACK", "station=${stationInfo.id} reason=$fallbackReason", "INFO")
                    Log.i(TAG, "Daily backfill NWS observations for ${stationInfo.id} are missing or stale ($fallbackReason). Querying Synoptic fallback...")
                    val minutes = WeatherConfig.NWS_BACKFILL_DAYS * 24 * 60L
                    val synopticList = synopticApi.fetchSynopticObservations(stationInfo.id, minutes, stationInfo.name).valueOrNull()
                    if (synopticList != null) {
                        isWeb = true
                        synopticList
                    } else {
                        observations
                    }
                } else {
                    observations
                }

                Log.d(TAG, "Station ${stationInfo.id} resolved ${finalObservations.size} observations")
                if (finalObservations.isNotEmpty()) {
                    val entities = finalObservations.map { obs ->
                        buildObservationEntity(obs, stationInfo, latitude, longitude, isWeb)
                    }
                    observationDao.insertAll(entities)
                    Log.i(TAG, "Successfully backfilled ${entities.size} observations from ${stationInfo.id}")
                    val distinctDays = entities.map { e ->
                        java.time.Instant.ofEpochMilli(e.timestamp).atZone(localZone).toLocalDate()
                    }.distinct()
                    for (day in distinctDays) {
                        recomputeDailyExtremesForDay(latitude, longitude, day, emptyList())
                    }
                    // A date is satisfied only once it has a row AND (for past days) the refetched
                    // observations now cover the afternoon — otherwise keep trying other stations.
                    val rowDates =
                        dailyHistoryDao.getExtremesInRange(dayMinus2Epoch, todayEpoch, latitude, longitude)
                            .filter { it.source == WeatherSource.NWS.id }
                            .map { it.date }
                            .toSet()
                    val stillIncomplete = incompletelyCoveredPastDates(rowDates, latitude, longitude, localZone, today)
                    remainingDates.removeAll(rowDates - stillIncomplete)
                    if (remainingDates.isEmpty()) {
                        break
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to backfill from ${stationInfo.id}: ${e.message}")
            }
        }

        if (remainingDates.isNotEmpty()) {
            Log.w(TAG, "Backfill completed but official NWS daily_history still missing/incomplete for $remainingDates")
        }
    }

    /**
     * From a set of day keys (epoch-millis at local midnight), returns those *past* days whose
     * stored NWS observations don't reach the afternoon — i.e. their cached daily high/low is
     * likely truncated and the day should be re-fetched. See [pastDayLacksAfternoonCoverage].
     */
    private suspend fun incompletelyCoveredPastDates(
        dayKeyEpochs: Set<Long>,
        latitude: Double,
        longitude: Double,
        zone: ZoneId,
        today: LocalDate,
    ): Set<Long> =
        dayKeyEpochs.filterTo(mutableSetOf()) { dayKeyEpoch ->
            val date = LocalDate.ofEpochDay(dayKeyEpoch / WidgetConstants.MS_IN_A_DAY)
            if (!date.isBefore(today)) return@filterTo false
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val timestamps =
                observationDao.getObservationsInRange(dayStart, dayEnd, latitude, longitude)
                    .filter { it.api == WeatherSource.NWS.id && it.stationId != "NWS_BLEND" }
                    .map { it.timestamp }
            pastDayLacksAfternoonCoverage(timestamps, date, zone, today)
        }

    internal suspend fun backfillRecentNwsObservations(
        latitude: Double,
        longitude: Double,
        lookbackHours: Long,
    ): RecentBackfillResult {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val startTimeStr = DateTimeFormatter.ISO_INSTANT.format(now.minusHours(lookbackHours).toInstant())
        val endTimeStr = DateTimeFormatter.ISO_INSTANT.format(now.toInstant())

        appLogDao.log(
            "OBS_HOURLY_BACKFILL_START",
            "lat=$latitude lon=$longitude lookbackHours=$lookbackHours start=$startTimeStr end=$endTimeStr",
            "INFO",
        )

        val gridPoint = runCatching { nwsApi.getGridPoint(latitude, longitude) }.getOrNull()
        val observationStationsUrl = gridPoint?.observationStationsUrl
        if (observationStationsUrl.isNullOrBlank()) {
            appLogDao.log(
                "OBS_HOURLY_BACKFILL_FAIL",
                "lat=$latitude lon=$longitude reason=missing_gridpoint_or_stations_url",
                "WARN",
            )
            return RecentBackfillResult(stationsTried = 0, rowsFetched = 0, affectedDates = emptySet())
        }

        // Smart-cast to non-null after the isNullOrBlank guard above (was previously two !! assertions).
        val stations = getSortedObservationStations(observationStationsUrl)
        if (stations.isEmpty()) {
            appLogDao.log(
                "OBS_HOURLY_BACKFILL_FAIL",
                "lat=$latitude lon=$longitude reason=no_stations",
                "WARN",
            )
            return RecentBackfillResult(stationsTried = 0, rowsFetched = 0, affectedDates = emptySet())
        }

        var totalRows = 0
        val affectedDates = mutableSetOf<LocalDate>()
        val localZone = ZoneId.systemDefault()
        val stationsToTry = stations.take(MAX_RETRIES)

        for ((index, stationInfo) in stationsToTry.withIndex()) {
            try {
                val observations = try {
                    nwsApi.getObservations(stationInfo.id, startTimeStr, endTimeStr)
                } catch (e: Exception) {
                    emptyList()
                }

                val newestObservationMs = observations.mapNotNull { it.observedAtMillis() }.maxOrNull()

                var isWeb = false
                val useWebFallback = synopticApi != null &&
                    ObservationFallbackPolicy.shouldUseWebFallback(index, newestObservationMs, System.currentTimeMillis())
                val finalObservations = if (useWebFallback) {
                    val fallbackReason = ObservationFallbackPolicy.fallbackReason(observations.size)
                    appLogDao.log("OBS_HOURLY_SYNOPTIC_FALLBACK", "station=${stationInfo.id} reason=$fallbackReason", "INFO")
                    Log.i(TAG, "Hourly NWS observations for ${stationInfo.id} are missing or stale ($fallbackReason). Querying Synoptic fallback...")
                    val minutes = lookbackHours * 60L
                    val synopticList = synopticApi.fetchSynopticObservations(stationInfo.id, minutes, stationInfo.name).valueOrNull()
                    if (synopticList != null) {
                        isWeb = true
                        synopticList
                    } else {
                        observations
                    }
                } else {
                    observations
                }

                appLogDao.log(
                    "OBS_HOURLY_BACKFILL_STATION",
                    "station=${stationInfo.id} rows=${finalObservations.size} lookbackHours=$lookbackHours",
                    "INFO",
                )
                if (finalObservations.isEmpty()) continue

                val entities = finalObservations.map { obs ->
                    buildObservationEntity(obs, stationInfo, latitude, longitude, isWeb)
                }
                observationDao.insertAll(entities)
                totalRows += entities.size
                affectedDates += entities.map { entity ->
                    java.time.Instant.ofEpochMilli(entity.timestamp).atZone(localZone).toLocalDate()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appLogDao.log(
                    "OBS_HOURLY_BACKFILL_STATION_FAIL",
                    "station=${stationInfo.id} ${e.message}",
                    "WARN",
                )
                Log.w(TAG, "Hourly observation backfill failed for ${stationInfo.id}: ${e.message}")
            }
        }

        if (affectedDates.isNotEmpty()) {
            recomputeDailyExtremesFromStoredObservations(
                latitude = latitude,
                longitude = longitude,
                startDate = affectedDates.min(),
                endDateInclusive = affectedDates.max(),
                hourlyForecasts = emptyList(),
            )
        }

        appLogDao.log(
            "OBS_HOURLY_BACKFILL_DONE",
            "lat=$latitude lon=$longitude stations=${stationsToTry.size} rows=$totalRows affectedDates=${affectedDates.sorted()}",
            "INFO",
        )
        return RecentBackfillResult(
            stationsTried = stationsToTry.size,
            rowsFetched = totalRows,
            affectedDates = affectedDates,
        )
    }

    /**
     * Returns daily actuals for the past 30 days from the DB cache, plus today's actuals
     * computed live from raw observations (never cached, always fresh).
     */
    suspend fun getDailyActualsWithLiveToday(
        latitude: Double,
        longitude: Double,
        hourlyForecasts: List<HourlyForecastEntity>,
        activeSourceList: List<String>,
    ): DailyActualsBySource {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()

        // Past days: read from DB cache
        val startDate = today.minusDays(30).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val endDate = today.minusDays(1).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val pastExtremes = dailyHistoryDao.getExtremesInRange(startDate, endDate, latitude, longitude)
        val pastActuals = ObservationResolver.extremesToDailyActualsBySource(pastExtremes, latitude, longitude)

        // Today: compute live from station observations using IDW blending (matches Hourly Graph).
        // Fetch a ±context window reaching back across midnight (not today-only) so stations whose
        // feed lapsed before midnight still bracket today's early-morning candidates. A today-only
        // window drops that coverage and lets a lone cold outlier dominate the low, so the daily
        // column diverged from the hourly graph. Extremes are still indexed by today's date below.
        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val contextObs = observationDao.getObservationsInRange(todayStartMs - ActualsAggregator.DAILY_BLEND_CONTEXT_MS, tomorrowMs, latitude, longitude)
            .filter { it.stationId != "NWS_BLEND" }
        val todayObs = contextObs.filter { it.timestamp in todayStartMs until tomorrowMs }

        val todayBlendedActuals = ObservationResolver.aggregateObservationsToDailyBySource(
            observations = contextObs,
            hourlyForecasts = hourlyForecasts,
            locationLat = latitude,
            locationLon = longitude,
            personalStationWeight = personalStationWeight(),
        )

        val obsSpanSummary =
            if (todayObs.isEmpty()) {
                "none"
            } else {
                val firstTs = todayObs.minOf { it.timestamp }
                val lastTs = todayObs.maxOf { it.timestamp }
                val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
                val firstLocal = java.time.Instant.ofEpochMilli(firstTs).atZone(zone).toLocalDateTime().format(formatter)
                val lastLocal = java.time.Instant.ofEpochMilli(lastTs).atZone(zone).toLocalDateTime().format(formatter)
                "$firstLocal..$lastLocal"
            }
        val liveSummary =
            todayBlendedActuals
                .toSortedMap()
                .entries
                .joinToString("; ") { (source, actualsByDate) ->
                    val actual = actualsByDate[today]
                    val stationCount = todayObs.count { it.api == source }
                    "$source[blendedHigh=${actual?.highTemp},blendedLow=${actual?.lowTemp},rows=$stationCount]"
                }
                .ifEmpty { "none" }
        Log.d(
            TAG,
            "getDailyActualsWithLiveToday: date=$today lat=$latitude lon=$longitude " +
                "todayObsRows=${todayObs.size} span=$obsSpanSummary live=[$liveSummary]",
        )

        // Today: use live blender result directly. Do NOT merge with persisted daily_history —
        // the persisted row is computed via IDW-of-per-station-max, which is a different algorithm
        // and routinely produces a different (higher) value than the IDW-by-hour blender used by
        // the Hourly Graph. Merging would re-introduce the 73.5° vs 73.1° discrepancy.
        return ObservationResolver.mergeDailyActualsBySource(
            primary = pastActuals,
            secondary = todayBlendedActuals,
        )
    }

    internal suspend fun recomputeDailyExtremesFromStoredObservations(
        latitude: Double,
        longitude: Double,
        startDate: LocalDate,
        endDateInclusive: LocalDate,
        hourlyForecasts: List<HourlyForecastEntity>,
    ) {
        val today = LocalDate.now()
        // Do not recompute daily extremes for days older than 9 days, as their observations
        // have been pruned (which would result in degenerate/corrupt daily extremes).
        val cutoffDate = today.minusDays(9)
        var current = startDate
        while (!current.isAfter(endDateInclusive)) {
            if (!current.isBefore(cutoffDate)) {
                recomputeDailyExtremesForDay(latitude, longitude, current, hourlyForecasts)
            } else {
                Log.d(TAG, "recomputeDailyExtremesFromStoredObservations: skipping pruned date $current")
            }
            current = current.plusDays(1)
        }
    }

    private suspend fun recomputeDailyExtremesForDay(
        latitude: Double,
        longitude: Double,
        date: LocalDate,
        hourlyForecasts: List<HourlyForecastEntity>,
    ) {
        val zone = ZoneId.systemDefault()
        val dateMillis = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val startTs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endTs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // Blend over a ±context window (not the day in isolation) so a station whose feed lapsed
        // near midnight still participates at the day's edge timestamps, matching the hourly graph.
        // computeDailyExtremes returns a row for every day present in the context obs, so filter
        // back to the target day; dayObs (day-only) still drives the empty check and the log.
        val contextStartTs = startTs - ActualsAggregator.DAILY_BLEND_CONTEXT_MS
        val contextEndTs = endTs + ActualsAggregator.DAILY_BLEND_CONTEXT_MS
        val contextObs = observationDao.getObservationsInRange(contextStartTs, contextEndTs, latitude, longitude)
        val dayObs = contextObs.filter { it.timestamp in startTs until endTs }
        if (dayObs.isEmpty()) return

        // Backfill paths pass no forecasts; load hourly forecasts across the same context window so
        // sources without measured observation precip (NWS) can fall back to forecast-derived rain.
        val effectiveHourly = hourlyForecasts.ifEmpty {
            hourlyForecastDao.getHourlyForecasts(contextStartTs, contextEndTs, latitude, longitude)
        }
        val newExtremes = ObservationResolver
            .computeDailyExtremes(contextObs, effectiveHourly, latitude, longitude, personalStationWeight())
            .filter { it.date == dateMillis }
        val existingHistory = dailyHistoryDao.getExtremesInRange(dateMillis, dateMillis, latitude, longitude)
            .groupBy { it.source }

        // Per-station breakdown — survives in app_logs so "why did the high jump?" investigations
        // don't need a live logcat capture.
        val perSourceBreakdown = dayObs
            .groupBy { it.api }
            .mapValues { (_, srcObs) ->
                srcObs.groupBy { it.stationId }.entries.joinToString(",") { (sid, obs) ->
                    val d = obs.first().distanceKm
                    val hi = obs.maxOf { it.temperature }
                    val lo = obs.minOf { it.temperature }
                    "$sid(d=${"%.2f".format(d)}km,hi=$hi,lo=$lo,n=${obs.size})"
                }
            }
        newExtremes.forEach { new ->
            val stationsStr = perSourceBreakdown[new.source] ?: "n/a"
            appLogDao.log(
                "DAILY_HISTORY_BLEND",
                "date=$date src=${new.source} computed_hi=${new.highTemp} computed_lo=${new.lowTemp} stations=[$stationsStr] userLat=$latitude userLon=$longitude",
                "DEBUG",
            )
        }

        // EXTREMA_WINDOW_DIAG (one-shot probe for the daily-bar vs hourly-graph high/low mismatch):
        // blend the NWS series two ways — day-isolated [startTs,endTs] (what daily_history uses) and a
        // wide ±24h window (closer to the hourly graph's blend context) — and log each argmax/argmin
        // timestamp. If the two windows agree, the window is NOT the cause (interpolation reach is only
        // 3h, so the ~3pm peak / ~5am trough are out of any edge's reach) and the gap lives in the
        // hourly pipeline. Compare against HOURLY_DAY_EXTREMA's hi@/lo@ timestamps for the same day.
        runCatching {
            val hourlyR = effectiveHourly.map { it.toHourlyForecast() }
            val fmt = DateTimeFormatter.ofPattern("HH:mm")
            fun probe(obs: List<ObservationReading>, s: Long, e: Long): String {
                val series = ActualTemperatureSeriesBuilder.blendObservationSeries(
                    observations = obs,
                    hourlyForecasts = hourlyR,
                    displaySourceId = WeatherSource.NWS.id,
                    userLat = latitude,
                    userLon = longitude,
                    startMs = s,
                    endMs = e,
                ).observations.filter { it.timestamp in startTs until endTs }
                if (series.isEmpty()) return "empty"
                val hi = series.maxByOrNull { it.temperature }!!
                val lo = series.minByOrNull { it.temperature }!!
                return "hi=${"%.2f".format(hi.temperature)}@${Instant.ofEpochMilli(hi.timestamp).atZone(zone).format(fmt)} " +
                    "lo=${"%.2f".format(lo.temperature)}@${Instant.ofEpochMilli(lo.timestamp).atZone(zone).format(fmt)} pts=${series.size}"
            }
            val nwsDayObs = dayObs.filter { it.api == WeatherSource.NWS.id }.map { it.toReading() }
            if (nwsDayObs.isNotEmpty()) {
                val dayMs = 24 * 3600_000L
                val wideObs = observationDao.getObservationsInRange(startTs - dayMs, endTs + dayMs, latitude, longitude)
                    .filter { it.api == WeatherSource.NWS.id }.map { it.toReading() }
                appLogDao.log(
                    "EXTREMA_WINDOW_DIAG",
                    "date=$date isolated=[${probe(nwsDayObs, startTs, endTs)}] wide=[${probe(wideObs, startTs - dayMs, endTs + dayMs)}]",
                    "DEBUG",
                )
            }
        }

        val toInsert = mutableListOf<com.weatherwidget.data.local.DailyHistoryEntity>()

        newExtremes.forEach { new ->
            // Overwrite for today and past days alike. Every recompute re-derives from the
            // full day's stored observations with an idempotent time-aligned blend, so a
            // real transient dip persists in the obs table and reappears in every re-blend;
            // the old today-only min/max ratchet could only preserve values that stopped
            // being reproducible from data — i.e. exactly the erroneous ones (a lone-station
            // outlier low stayed pinned all day despite correct recomputes). Overwrite also
            // matches desktop and lets self-healing migration replace stale rows left by
            // the old per-station-spot-max algorithm.
            //
            // Heal EVERY location fragment of this (date, source) in the match box, not just
            // the row at the recompute coordinates: the fetch and the widget display can
            // resolve different coordinates (GPS fix vs default), and the display picks the
            // fragment nearest ITS location — a recompute that only wrote its own fragment
            // left the displayed one stale. All coordinates in the box read the same
            // observation rows, so the blended extremes are identical for every fragment.
            val fragments = existingHistory[new.source].orEmpty()
            if (fragments.isEmpty()) {
                toInsert.add(new)
                return@forEach
            }
            var changedAny = false
            fragments.forEach { existing ->
                if (new.highTemp != existing.highTemp || new.lowTemp != existing.lowTemp || new.condition != existing.condition || precipChanged(new, existing)) {
                    changedAny = true
                    appLogDao.log(
                        "DAILY_HISTORY_OVERWRITE",
                        "date=$date src=${new.source} at=${existing.locationLat},${existing.locationLon} high=${existing.highTemp}->${new.highTemp} low=${existing.lowTemp}->${new.lowTemp} precip=${existing.precipAmountMm}->${new.precipAmountMm}",
                        "DEBUG",
                    )
                    // This is a full-row REPLACE (insertAll uses OnConflictStrategy.REPLACE): carry
                    // over the resolved forecast chance snapshot and the frozen display columns the
                    // freeze writer stored, since `new` (rebuilt from raw observations) never
                    // populates those fields itself.
                    toInsert.add(
                        new.copy(
                            locationLat = existing.locationLat,
                            locationLon = existing.locationLon,
                            forecastDayPrecipChance = existing.forecastDayPrecipChance,
                            forecastNightPrecipChance = existing.forecastNightPrecipChance,
                            forecastHighTemp = existing.forecastHighTemp,
                            forecastLowTemp = existing.forecastLowTemp,
                            forecastPrecipAmountMm = existing.forecastPrecipAmountMm,
                            noonCloudPercent = existing.noonCloudPercent,
                        ),
                    )
                }
            }
            if (!changedAny) {
                appLogDao.log("DAILY_HISTORY_STABLE", "date=$date src=${new.source} high=${new.highTemp} low=${new.lowTemp} fragments=${fragments.size}", "DEBUG")
            }
        }

        if (toInsert.isNotEmpty()) {
            dailyHistoryDao.insertAll(toInsert)
        }
    }

    private fun precipChanged(
        new: com.weatherwidget.data.local.DailyHistoryEntity,
        existing: com.weatherwidget.data.local.DailyHistoryEntity,
    ): Boolean =
        new.precipAmountMm != existing.precipAmountMm ||
            new.precipDayMm != existing.precipDayMm ||
            new.precipNightMm != existing.precipNightMm

    suspend fun getRecentObservations(sinceMs: Long): List<ObservationEntity> =
        observationDao.getRecentObservations(sinceMs)

    /**
     * Recent observations scoped to the given location's ~0.1° box (see [LocationMatch]). Prefer this
     * over [getRecentObservations] anywhere a location is known, so observations fetched under a
     * previously-visited location don't leak into the current location's list.
     */
    suspend fun getRecentObservationsNear(
        sinceMs: Long,
        latitude: Double,
        longitude: Double,
    ): List<ObservationEntity> =
        observationDao.getRecentObservationsNear(sinceMs, latitude, longitude)

    suspend fun getMainObservationsWithComputedNwsBlend(
        latitude: Double,
        longitude: Double,
        sinceMs: Long,
    ): List<ObservationEntity> = coroutineScope {
        val persistedMainObs = observationDao.getLatestMainObservationsExcludingNws(latitude, longitude, sinceMs)
        val nwsStationObsAll = observationDao.getLatestNwsObservationsByStationAllTime(latitude, longitude, sinceMs)

        Log.d(TAG, "getMainObservationsWithComputedNwsBlend: persistedMainObs=${persistedMainObs.size} nwsStationObsAll=${nwsStationObsAll.size} sinceMs=${sinceMs}")
        nwsStationObsAll.take(10).forEach { obs ->
            Log.d(TAG, "  NWS station ${obs.stationId}: timestamp=${obs.timestamp} fetchedAt=${obs.fetchedAt}")
        }

        val nwsStationObs = nwsStationObsAll.filter { it.timestamp > sinceMs }
        Log.d(TAG, "getMainObservationsWithComputedNwsBlend: after filtering by sinceMs: ${nwsStationObs.size}")

        if (nwsStationObs.isEmpty()) {
            Log.d(TAG, "getMainObservationsWithComputedNwsBlend: no NWS station obs after filter, returning persisted only")
            return@coroutineScope persistedMainObs
        }

        val dedupedNwsObs = nwsStationObs
            .groupBy { it.stationId }
            // groupBy guarantees each value list is non-empty, so maxByOrNull can never be null here.
            .mapValues { it.value.maxByOrNull { it.timestamp }!! }
            .values
            .toList()

        if (dedupedNwsObs.isEmpty()) {
            Log.d(TAG, "getMainObservationsWithComputedNwsBlend: deduped empty, returning persisted only")
            return@coroutineScope persistedMainObs
        }

        Log.d(TAG, "getMainObservationsWithComputedNwsBlend: dedupedNwsObs=${dedupedNwsObs.size}")
        dedupedNwsObs.forEach { obs ->
            Log.d(TAG, "  deduped ${obs.stationId}: timestamp=${obs.timestamp}")
        }

        val blendedTemp = SpatialInterpolator.interpolateIDW(latitude, longitude, dedupedNwsObs.map { it.toReading() })
            ?: return@coroutineScope persistedMainObs

        val closest = dedupedNwsObs.minBy { it.distanceKm }
        val newestTimestamp = dedupedNwsObs.maxOf { it.timestamp }
        val newestFetchedAt = dedupedNwsObs.maxOf { it.fetchedAt }

        val syntheticNwsMain = ObservationEntity(
            stationId = "NWS_BLEND",
            stationName = "NWS Blended",
            timestamp = newestTimestamp,
            temperature = blendedTemp,
            condition = closest.condition,
            locationLat = latitude,
            locationLon = longitude,
            distanceKm = 0f,
            stationType = "BLENDED",
            fetchedAt = newestFetchedAt,
            api = WeatherSource.NWS.id,
            isWebFallback = false,
        )

        persistedMainObs + syntheticNwsMain
    }

    private fun buildObservationEntity(
        obs: NwsApi.Observation,
        stationInfo: NwsApi.StationInfo,
        latitude: Double,
        longitude: Double,
        isWebFallback: Boolean = false,
    ): ObservationEntity {
        val distanceKm = calculateDistance(latitude, longitude, stationInfo.lat, stationInfo.lon) / 1000f
        return ObservationEntity(
            stationId = stationInfo.id,
            stationName = obs.stationName.ifEmpty { stationInfo.name },
            timestamp = OffsetDateTime.parse(obs.timestamp).toInstant().toEpochMilli(),
            temperature = (obs.temperatureCelsius * 1.8f) + 32f,
            condition = obs.textDescription,
            locationLat = latitude,
            locationLon = longitude,
            distanceKm = distanceKm,
            stationType = stationInfo.type.name,
            maxTempLast24h = obs.maxTempLast24hCelsius?.let { (it * 1.8f) + 32f },
            minTempLast24h = obs.minTempLast24hCelsius?.let { (it * 1.8f) + 32f },
            api = WeatherSource.NWS.id,
            precipAmountMm = obs.precipLastHourMm,
            isWebFallback = isWebFallback,
            qcFailed = obs.qcFailed,
        )
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }
}
