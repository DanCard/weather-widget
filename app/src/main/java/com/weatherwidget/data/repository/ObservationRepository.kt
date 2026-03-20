package com.weatherwidget.data.repository

import android.content.Context
import android.location.Location
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyExtremeDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.util.SpatialInterpolator
import com.weatherwidget.widget.ObservationResolver
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

@Singleton
class ObservationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val observationDao: ObservationDao,
    private val dailyExtremeDao: DailyExtremeDao,
    private val appLogDao: AppLogDao,
    private val nwsApi: NwsApi
) {
    internal data class RecentBackfillResult(
        val stationsTried: Int,
        val rowsFetched: Int,
        val affectedDates: Set<LocalDate>,
    )

    private val prefs by lazy { com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, "weather_prefs") }

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

        val closestDeferred = async {
            val retryDelaysMs = listOf(60_000L, 120_000L, 240_000L)
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

        if (successfulEntities.isEmpty()) return@coroutineScope null

        val blendedTemp = SpatialInterpolator.interpolateIDW(latitude, longitude, successfulEntities)
            ?: return@coroutineScope null

        val closest = successfulEntities.minBy { it.distanceKm }

        val stationSummary = successfulEntities.joinToString { "${it.stationId}(${it.distanceKm}km)" }
        appLogDao.log("NWS_IDW", "blended=${blendedTemp}°F from ${successfulEntities.size} stations: $stationSummary")
        Log.d(TAG, "NWS IDW blend: $blendedTemp°F from $stationSummary")

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
        val result = runCatching { nwsApi.getLatestObservationDetailed(stationInfo.id) }
        val observation = result.getOrNull()
        if (observation == null) {
            val reason = result.exceptionOrNull()?.message ?: "null_response"
            appLogDao.log("NWS_STATION_FAIL", "station=${stationInfo.id} attempt=$attempt reason=$reason", "WARN")
            Log.w(TAG, "NWS station ${stationInfo.id} attempt $attempt failed: $reason")
            return null
        }
        if (attempt > 0) {
            appLogDao.log("NWS_STATION_RETRY_OK", "station=${stationInfo.id} attempt=$attempt", "INFO")
            Log.d(TAG, "NWS station ${stationInfo.id} succeeded on retry attempt $attempt")
        }
        val distanceKm = calculateDistance(latitude, longitude, stationInfo.lat, stationInfo.lon) / 1000f
        val obsEntity = ObservationEntity(
            stationInfo.id,
            observation.stationName,
            OffsetDateTime.parse(observation.timestamp).toInstant().toEpochMilli(),
            (observation.temperatureCelsius * 1.8f) + 32f,
            observation.textDescription,
            latitude,
            longitude,
            distanceKm,
            stationInfo.type.name,
            maxTempLast24h = observation.maxTempLast24hCelsius?.let { (it * 1.8f) + 32f },
            minTempLast24h = observation.minTempLast24hCelsius?.let { (it * 1.8f) + 32f },
        )
        observationDao.insertAll(listOf(obsEntity))
        recomputeDailyExtremesForDay(latitude, longitude, obsEntity.timestamp)
        return obsEntity
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

        val yesterday = now.minusDays(1).toLocalDate()
        val today = now.toLocalDate()
        val currentHour = now.hour
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val requiredDates = buildSet {
            add(yesterdayStr)
            if (currentHour >= 2) add(todayStr)
        }
        val existingDates =
            dailyExtremeDao.getExtremesInRange(yesterdayStr, todayStr, latitude, longitude)
                .filter { it.source == WeatherSource.NWS.id }
                .map { it.date }
                .toSet()
        val missingDates = requiredDates - existingDates

        Log.d(TAG, "History check: requiredDates=$requiredDates existingDates=$existingDates missingDates=$missingDates hour=$currentHour")

        if (missingDates.isEmpty()) {
            Log.d(TAG, "Skipping backfill: required NWS daily_extremes rows already exist")
            return
        }

        Log.i(TAG, "Missing NWS daily_extremes for $missingDates, backfilling last 48 hours")
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
        val remainingDates = missingDates.toMutableSet()

        for (stationInfo in stations.take(3)) {
            Log.d(TAG, "Attempting backfill from station ${stationInfo.id}")
            try {
                val observations = nwsApi.getObservations(stationInfo.id, startTimeStr, endTimeStr)
                Log.d(TAG, "Station ${stationInfo.id} returned ${observations.size} observations")
                if (observations.isNotEmpty()) {
                    val entities = observations.map { obs ->
                        val distanceKm = calculateDistance(latitude, longitude, stationInfo.lat, stationInfo.lon) / 1000f
                        ObservationEntity(
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
                        )
                    }
                    observationDao.insertAll(entities)
                    Log.i(TAG, "Successfully backfilled ${entities.size} observations from ${stationInfo.id}")
                    val distinctDayTimestamps = entities.map { e ->
                        java.time.Instant.ofEpochMilli(e.timestamp).atZone(localZone).toLocalDate()
                            .atStartOfDay(localZone).toInstant().toEpochMilli()
                    }.distinct()
                    for (dayTs in distinctDayTimestamps) {
                        recomputeDailyExtremesForDay(latitude, longitude, dayTs + 3_600_000L)
                    }
                    val refreshedDates =
                        dailyExtremeDao.getExtremesInRange(yesterdayStr, todayStr, latitude, longitude)
                            .filter { it.source == WeatherSource.NWS.id }
                            .map { it.date }
                            .toSet()
                    remainingDates.removeAll(refreshedDates)
                    if (remainingDates.isEmpty()) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to backfill from ${stationInfo.id}: ${e.message}")
            }
        }

        if (remainingDates.isNotEmpty()) {
            Log.w(TAG, "Backfill completed but official NWS daily_extremes still missing for $remainingDates")
        }
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
        if (gridPoint?.observationStationsUrl.isNullOrBlank()) {
            appLogDao.log(
                "OBS_HOURLY_BACKFILL_FAIL",
                "lat=$latitude lon=$longitude reason=missing_gridpoint_or_stations_url",
                "WARN",
            )
            return RecentBackfillResult(stationsTried = 0, rowsFetched = 0, affectedDates = emptySet())
        }

        val stations = getSortedObservationStations(checkNotNull(gridPoint?.observationStationsUrl))
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

        for (stationInfo in stationsToTry) {
            try {
                val observations = nwsApi.getObservations(stationInfo.id, startTimeStr, endTimeStr)
                appLogDao.log(
                    "OBS_HOURLY_BACKFILL_STATION",
                    "station=${stationInfo.id} rows=${observations.size} lookbackHours=$lookbackHours",
                    "INFO",
                )
                if (observations.isEmpty()) continue

                val distanceKm = calculateDistance(latitude, longitude, stationInfo.lat, stationInfo.lon) / 1000f
                val entities =
                    observations.map { obs ->
                        ObservationEntity(
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
                        )
                    }
                observationDao.insertAll(entities)
                totalRows += entities.size
                affectedDates += entities.map { entity ->
                    java.time.Instant.ofEpochMilli(entity.timestamp).atZone(localZone).toLocalDate()
                }
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

    internal suspend fun recomputeDailyExtremesFromStoredObservations(
        latitude: Double,
        longitude: Double,
        startDate: LocalDate,
        endDateInclusive: LocalDate,
    ) {
        val zone = ZoneId.systemDefault()
        val startTs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endTs = endDateInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val observations = observationDao.getObservationsInRange(startTs, endTs, latitude, longitude)
        if (observations.isEmpty()) return

        val extremes = ObservationResolver.computeDailyExtremes(observations, latitude, longitude)
        if (extremes.isNotEmpty()) {
            dailyExtremeDao.insertAll(extremes)
        }
    }

    private suspend fun recomputeDailyExtremesForDay(
        latitude: Double,
        longitude: Double,
        referenceTimestamp: Long,
    ) {
        val zone = ZoneId.systemDefault()
        val day = java.time.Instant.ofEpochMilli(referenceTimestamp).atZone(zone).toLocalDate()
        val startTs = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val endTs = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayObs = observationDao.getObservationsInRange(startTs, endTs, latitude, longitude)
        if (dayObs.isNotEmpty()) {
            val extremes = ObservationResolver.computeDailyExtremes(dayObs, latitude, longitude)
            dailyExtremeDao.insertAll(extremes)
        }
    }

    suspend fun getRecentObservations(sinceMs: Long): List<ObservationEntity> =
        observationDao.getRecentObservations(sinceMs)

    suspend fun getMainObservationsWithComputedNwsBlend(
        latitude: Double,
        longitude: Double,
        sinceMs: Long,
    ): List<ObservationEntity> = coroutineScope {
        val persistedMainObs = observationDao.getLatestMainObservationsExcludingNws(latitude, longitude, sinceMs)
        val nwsStationObs = observationDao.getLatestNwsObservationsByStation(latitude, longitude, sinceMs)

        if (nwsStationObs.isEmpty()) {
            return@coroutineScope persistedMainObs
        }

        val dedupedNwsObs = nwsStationObs
            .groupBy { it.stationId }
            .mapValues { it.value.maxByOrNull { it.timestamp }!! }
            .values
            .toList()

        if (dedupedNwsObs.isEmpty()) {
            return@coroutineScope persistedMainObs
        }

        val blendedTemp = SpatialInterpolator.interpolateIDW(latitude, longitude, dedupedNwsObs)
            ?: return@coroutineScope persistedMainObs

        val closest = dedupedNwsObs.minBy { it.distanceKm }
        val newestTimestamp = dedupedNwsObs.maxOf { it.timestamp }
        val newestFetchedAt = dedupedNwsObs.maxOf { it.fetchedAt }

        val syntheticNwsMain = ObservationEntity(
            stationId = "NWS_MAIN",
            stationName = "NWS Blended",
            timestamp = newestTimestamp,
            temperature = blendedTemp,
            condition = closest.condition,
            locationLat = latitude,
            locationLon = longitude,
            distanceKm = 0f,
            stationType = "BLENDED",
            fetchedAt = newestFetchedAt,
        )

        persistedMainObs + syntheticNwsMain
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }
}
