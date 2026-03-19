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
import kotlin.math.roundToInt

private const val TAG = "ObservationRepository"

@Singleton
class ObservationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val observationDao: ObservationDao,
    private val dailyExtremeDao: DailyExtremeDao,
    private val appLogDao: AppLogDao,
    private val nwsApi: NwsApi
) {
    private val prefs by lazy { com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, "weather_prefs") }

    internal data class ObservationResult(
        val highTemp: Float,
        val lowTemp: Float,
        val stationId: String,
        val condition: String
    )

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

    internal suspend fun fetchDayObservations(stationUrl: String, date: LocalDate): ObservationResult? {
        if (stationUrl.isEmpty()) return null
        return fetchDayObservations(getSortedObservationStations(stationUrl), date)
    }

    internal suspend fun fetchDayObservations(stations: List<NwsApi.StationInfo>, date: LocalDate): ObservationResult? {
        val localZone = ZoneId.systemDefault()
        val startTimeStr = date.atStartOfDay(localZone).format(DateTimeFormatter.ISO_INSTANT)
        val endTimeStr = date.plusDays(1).atStartOfDay(localZone).format(DateTimeFormatter.ISO_INSTANT)

        for (stationInfo in stations.take(MAX_RETRIES)) {
            try {
                val observations = nwsApi.getObservations(stationInfo.id, startTimeStr, endTimeStr)
                if (observations.isEmpty()) {
                    appLogDao.log("NWS_STATION_FAIL", "station=${stationInfo.id} date=$date reason=empty_observations", "WARN")
                    continue
                }

                val temperaturesF = observations.map { (it.temperatureCelsius * 1.8f) + 32f }
                val highTemp = temperaturesF.maxOrNull() ?: continue
                val lowTemp = temperaturesF.minOrNull() ?: continue

                val daylightObservations = observations.filter {
                    runCatching { ZonedDateTime.parse(it.timestamp).withZoneSameInstant(localZone).hour }.getOrDefault(12) in 7..19
                }.ifEmpty { observations }

                val hasPrecipitation = daylightObservations.any {
                    val description = it.textDescription.lowercase()
                    description.contains("rain") || description.contains("shower") || description.contains("storm") || description.contains("snow")
                }

                val cloudScores = daylightObservations.map {
                    val description = it.textDescription.lowercase()
                    when {
                        description.contains("mostly cloudy") -> 75
                        description.contains("mostly clear") || description.contains("mostly sunny") -> 25
                        description.contains("partly") -> 50
                        description.contains("cloudy") || description.contains("overcast") -> 100
                        description.contains("fair") || description.contains("sunny") || description.contains("clear") -> 0
                        else -> 50
                    }
                }

                val averageCloudScore = if (cloudScores.isNotEmpty()) cloudScores.average() else 50.0
                val baseCondition = if (hasPrecipitation) "Rain" else when {
                    averageCloudScore <= 15 -> "Sunny"
                    averageCloudScore <= 35 -> "Mostly Sunny"
                    averageCloudScore <= 65 -> "Partly Cloudy"
                    averageCloudScore <= 85 -> "Mostly Cloudy"
                    else -> "Cloudy"
                }

                val finalCondition = if (averageCloudScore == 0.0 || averageCloudScore == 100.0) baseCondition else "$baseCondition (${averageCloudScore.roundToInt()}%)"

                return ObservationResult(highTemp, lowTemp, stationInfo.id, finalCondition)
            } catch (exception: Exception) {
                appLogDao.log("NWS_STATION_FAIL", "station=${stationInfo.id} date=$date reason=${exception.message}", "WARN")
            }
        }
        return null
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
        val startTsYesterday = yesterday.atStartOfDay(localZone).toInstant().toEpochMilli()
        val endTsYesterday = yesterday.plusDays(1).atStartOfDay(localZone).toInstant().toEpochMilli()

        val yesterdayObservations = observationDao.getObservationsInRange(startTsYesterday, endTsYesterday, latitude, longitude)
        val isYesterdayPopulated = yesterdayObservations.size >= 10

        val today = now.toLocalDate()
        val startTsToday = today.atStartOfDay(localZone).toInstant().toEpochMilli()
        val endTsToday = now.toInstant().toEpochMilli()

        val todayObservations = observationDao.getObservationsInRange(startTsToday, endTsToday, latitude, longitude)
        val currentHour = now.hour
        val isTodayPopulated = when {
            currentHour < 2 -> true
            currentHour < 6 -> todayObservations.size >= 2
            currentHour < 12 -> todayObservations.size >= 4
            else -> todayObservations.size >= 8
        }

        Log.d(TAG, "History check: yesterdayCount=${yesterdayObservations.size}, todayCount=${todayObservations.size}, hour=$currentHour")

        if (isYesterdayPopulated && isTodayPopulated) {
            Log.d(TAG, "Skipping backfill: both yesterday and today already have sufficient data")
            return
        }

        Log.i(TAG, "Insufficient history (yesterdayPopulated=$isYesterdayPopulated, todayPopulated=$isTodayPopulated), backfilling last 48 hours")
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
                        val zone = ZoneId.systemDefault()
                        java.time.Instant.ofEpochMilli(e.timestamp).atZone(zone).toLocalDate()
                            .atStartOfDay(zone).toInstant().toEpochMilli()
                    }.distinct()
                    for (dayTs in distinctDayTimestamps) {
                        recomputeDailyExtremesForDay(latitude, longitude, dayTs + 3_600_000L)
                    }
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to backfill from ${stationInfo.id}: ${e.message}")
            }
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

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }
}
