package com.weatherwidget.data.repository

import android.content.Context
import android.location.Location
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.CurrentTempDao
import com.weatherwidget.data.local.CurrentTempEntity
import com.weatherwidget.data.local.HourlyActualDao
import com.weatherwidget.data.local.HourlyActualEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.data.remote.SilurianApi
import com.weatherwidget.util.SpatialInterpolator
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val TAG = "CurrentTempRepository"

@Singleton
class CurrentTempRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val currentTempDao: CurrentTempDao,
        private val observationDao: ObservationDao,
        private val hourlyActualDao: HourlyActualDao,
        private val hourlyForecastDao: HourlyForecastDao,
        private val appLogDao: AppLogDao,
        private val nwsApi: NwsApi,
        private val openMeteoApi: OpenMeteoApi,
        private val weatherApi: WeatherApi,
        private val silurianApi: SilurianApi,
        private val widgetStateManager: WidgetStateManager,
        private val temperatureInterpolator: TemperatureInterpolator,
    ) {
        private val syncMutex = Mutex()
        companion object { 
            private const val CURRENT_TEMP_FRESHNESS_MS = 300_000L // 5 minutes
            private const val MAX_RETRIES = 5 
        }
        
        private var lastFetchTime: Long
            get() = FetchMetadata.getLastCurrentTempFetchTime(context)
            set(value) = FetchMetadata.setLastCurrentTempFetchTime(context, value)
            
        private val prefs by lazy { com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, "weather_prefs") }

        suspend fun refreshCurrentTemperature(
            latitude: Double, 
            longitude: Double, 
            locationName: String, 
            source: WeatherSource? = null, 
            reason: String = "unspecified", 
            forceRefresh: Boolean = false
        ): Result<Int> {
            return try {
                syncMutex.withLock {
                    val currentTime = System.currentTimeMillis()
                    if (!forceRefresh && currentTime - lastFetchTime < CURRENT_TEMP_FRESHNESS_MS) {
                        return Result.success(0)
                    }
                    
                    recordHistoricalPoi(latitude, longitude, locationName)
                    val enabledSources = widgetStateManager.getVisibleSourcesOrder()
                    val targetSources = (source?.let { requested ->
                        if (requested in enabledSources) listOf(requested) else emptyList()
                    } ?: enabledSources)
                        .filter { it != WeatherSource.GENERIC_GAP }
                        .distinct()
                        
                    appLogDao.log("CURR_FETCH_START", "reason=$reason targets=${targetSources.joinToString { it.id }}")
                    
                    targetSources.forEach { targetSource ->
                        try {
                            val reading = fetchFromSource(targetSource, latitude, longitude) ?: return@forEach
                            currentTempDao.insert(
                                CurrentTempEntity(
                                    LocalDate.now().toString(), 
                                    reading.source.id, 
                                    latitude, 
                                    longitude, 
                                    reading.temperature, 
                                    reading.observedAt ?: currentTime, 
                                    reading.condition, 
                                    currentTime
                                )
                            )
                        } catch (exception: Exception) { 
                            appLogDao.log("CURR_FETCH_ERROR", "source=${targetSource.id} error=${exception.message}", "WARN") 
                        }
                    }
                    
                    lastFetchTime = System.currentTimeMillis()
                    Result.success(targetSources.size)
                }
            } catch (exception: Exception) { 
                Result.failure(exception) 
            }
        }

        private suspend fun fetchFromSource(source: WeatherSource, latitude: Double, longitude: Double): CurrentReadingPayload? =
            when (source) {
                WeatherSource.OPEN_METEO -> fetchOpenMeteoCurrent(latitude, longitude)
                WeatherSource.WEATHER_API -> fetchWeatherApiCurrent(latitude, longitude)
                WeatherSource.NWS -> fetchNwsCurrent(latitude, longitude)
                WeatherSource.SILURIAN -> fetchSilurianCurrent(latitude, longitude)
                else -> null
            }

        private suspend fun fetchSilurianCurrent(latitude: Double, longitude: Double): CurrentReadingPayload? = coroutineScope {
            val pointsOfInterest = getPointsOfInterest(latitude, longitude)
            val deferredReadings = pointsOfInterest.mapIndexed { index, point ->
                async {
                    val reading = runCatching { silurianApi.getForecast(point.first, point.second, 1) }.getOrNull()
                    if (reading?.currentTemp != null) {
                        val stationId = if (point.third == "Current") "SILURIAN_MAIN" else "SILURIAN_$index"
                        val obsTime = reading.currentObservedAt ?: System.currentTimeMillis()
                        val obsEntity = ObservationEntity(
                            stationId = stationId,
                            stationName = "Silurian: ${point.third}",
                            timestamp = obsTime,
                            temperature = reading.currentTemp,
                            condition = reading.currentCondition ?: "Unknown",
                            locationLat = latitude,
                            locationLon = longitude
                        )
                        observationDao.insertAll(listOf(obsEntity))
                        hourlyActualDao.insertAll(listOf(observationToActual(obsEntity, com.weatherwidget.data.model.WeatherSource.SILURIAN.id)))
                    }
                    reading
                }
            }.map { it.await() }

            deferredReadings.firstNotNullOfOrNull { it }?.let { reading ->
                if (reading.currentTemp != null) {
                    CurrentReadingPayload(WeatherSource.SILURIAN, reading.currentTemp, reading.currentCondition, reading.currentObservedAt)
                } else null
            }
        }
        private suspend fun fetchOpenMeteoCurrent(latitude: Double, longitude: Double): CurrentReadingPayload? = coroutineScope {
            val pointsOfInterest = getPointsOfInterest(latitude, longitude)
            val deferredReadings = pointsOfInterest.mapIndexed { index, point ->
                async {
                    val reading = runCatching { openMeteoApi.getCurrent(point.first, point.second) }.getOrNull()
                    if (reading != null) {
                        val condition = reading.weatherCode?.let { openMeteoApi.weatherCodeToCondition(it) } ?: "Unknown"
                        val stationId = if (point.third == "Current") "OPEN_METEO_MAIN" else "OPEN_METEO_$index"
                        val obsEntity = ObservationEntity(
                            stationId,
                            "Meteo: ${point.third}",
                            reading.observedAt ?: System.currentTimeMillis(),
                            reading.temperature,
                            condition,
                            latitude,
                            longitude,
                            calculateDistance(latitude, longitude, point.first, point.second) / 1000f,
                            "OFFICIAL"
                        )
                        observationDao.insertAll(listOf(obsEntity))
                        hourlyActualDao.insertAll(listOf(observationToActual(obsEntity, com.weatherwidget.data.model.WeatherSource.OPEN_METEO.id)))
                    }
                    reading
                }
            }.map { it.await() }
            
            deferredReadings.firstNotNullOfOrNull { it }?.let { reading ->
                CurrentReadingPayload(
                    WeatherSource.OPEN_METEO, 
                    reading.temperature, 
                    reading.weatherCode?.let { openMeteoApi.weatherCodeToCondition(it) }, 
                    reading.observedAt
                ) 
            }
        }

        private suspend fun fetchWeatherApiCurrent(latitude: Double, longitude: Double): CurrentReadingPayload? = coroutineScope {
            val pointsOfInterest = getPointsOfInterest(latitude, longitude)
            val deferredReadings = pointsOfInterest.mapIndexed { index, point ->
                async {
                    val reading = runCatching { weatherApi.getCurrent(point.first, point.second) }.getOrNull()
                    if (reading != null) {
                        val stationId = if (point.third == "Current") "WEATHER_API_MAIN" else "WEATHER_API_$index"
                        val obsEntity = ObservationEntity(
                            stationId,
                            "WAPI: ${point.third}",
                            reading.observedAt ?: System.currentTimeMillis(),
                            reading.temperature,
                            reading.condition ?: "Unknown",
                            latitude,
                            longitude,
                            calculateDistance(latitude, longitude, point.first, point.second) / 1000f,
                            "OFFICIAL"
                        )
                        observationDao.insertAll(listOf(obsEntity))
                        hourlyActualDao.insertAll(listOf(observationToActual(obsEntity, com.weatherwidget.data.model.WeatherSource.WEATHER_API.id)))
                    }
                    reading
                }
            }.map { it.await() }
            
            deferredReadings.firstNotNullOfOrNull { it }?.let { reading ->
                CurrentReadingPayload(WeatherSource.WEATHER_API, reading.temperature, reading.condition, reading.observedAt) 
            }
        }

        private suspend fun fetchNwsCurrent(latitude: Double, longitude: Double): CurrentReadingPayload? = coroutineScope {
            val gridPoint = nwsApi.getGridPoint(latitude, longitude)
            val stations = getSortedObservationStations(gridPoint.observationStationsUrl ?: "")
            if (stations.isEmpty()) return@coroutineScope null
            
            val successfulEntities = stations.take(MAX_RETRIES).map { stationInfo ->
                async {
                    val observation = runCatching { nwsApi.getLatestObservationDetailed(stationInfo.id) }.getOrNull()
                        ?: return@async null
                    val obsEntity = ObservationEntity(
                        stationInfo.id,
                        observation.stationName,
                        OffsetDateTime.parse(observation.timestamp).toInstant().toEpochMilli(),
                        (observation.temperatureCelsius * 1.8f) + 32f,
                        observation.textDescription,
                        latitude,
                        longitude,
                        calculateDistance(latitude, longitude, stationInfo.lat, stationInfo.lon) / 1000f,
                        stationInfo.type.name
                    )
                    observationDao.insertAll(listOf(obsEntity))
                    hourlyActualDao.insertAll(listOf(observationToActual(obsEntity, com.weatherwidget.data.model.WeatherSource.NWS.id)))
                    obsEntity
                }
            }.mapNotNull { it.await() }

            if (successfulEntities.isEmpty()) return@coroutineScope null

            val blendedTemp = SpatialInterpolator.interpolateIDW(latitude, longitude, successfulEntities)
                ?: return@coroutineScope null

            // Use the closest station's condition (conditions don't interpolate)
            val closest = successfulEntities.minBy { it.distanceKm }

            // Log contributing stations and their weights for debugging
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

        private fun getPointsOfInterest(latitude: Double, longitude: Double): List<Triple<Double, Double, String>> {
            val points = mutableListOf(
                Triple(latitude, longitude, "Current"), 
                Triple(latitude + 0.072, longitude, "North"), 
                Triple(latitude - 0.072, longitude, "South"), 
                Triple(latitude, longitude + 0.09, "East"), 
                Triple(latitude, longitude - 0.09, "West")
            )
            
            getHistoricalPois().forEach { (histLat, histLon, histName) -> 
                if (calculateDistance(latitude, longitude, histLat, histLon) > 1000) {
                    points.add(Triple(histLat, histLon, "Recent: $histName")) 
                }
            }
            return points.distinctBy { "${it.first},${it.second}" }
        }

        suspend fun getInterpolatedTemperature(
            latitude: Double, 
            longitude: Double, 
            time: LocalDateTime = LocalDateTime.now()
        ): Float? {
            val startDateTime = time.minusHours(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val endDateTime = time.plusHours(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val hourlyForecasts = hourlyForecastDao.getHourlyForecasts(startDateTime, endDateTime, latitude, longitude)
            
            return if (hourlyForecasts.isEmpty()) null else temperatureInterpolator.getInterpolatedTemperature(hourlyForecasts, time)
        }

        suspend fun getNextInterpolationUpdateTime(
            latitude: Double, 
            longitude: Double, 
            time: LocalDateTime = LocalDateTime.now()
        ): LocalDateTime {
            val currentHourStr = time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val nextHourStr = time.plusHours(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val forecasts = hourlyForecastDao.getHourlyForecasts(currentHourStr, nextHourStr, latitude, longitude)
            
            val tempDiff = if (forecasts.size >= 2) (forecasts[1].temperature - forecasts[0].temperature).toInt() else 0
            return temperatureInterpolator.getNextUpdateTime(time, tempDiff)
        }

        private suspend fun getSortedObservationStations(stationsUrl: String): List<NwsApi.StationInfo> {
            if (stationsUrl.isEmpty()) return emptyList()
            
            val stationsKey = "observation_stations_v3_${stationsUrl.hashCode()}"
            val timeKey = "observation_stations_time_v3_${stationsUrl.hashCode()}"
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

        @androidx.annotation.VisibleForTesting
        internal fun recordHistoricalPoi(latitude: Double, longitude: Double, name: String) {
            val poiStrings = prefs.getString("historical_pois", "")!!.split(";").filter { it.isNotEmpty() }.toMutableList()
            poiStrings.removeIf { it.contains("|$latitude|$longitude") }
            poiStrings.add(0, "$name|$latitude|$longitude")
            prefs.edit().putString("historical_pois", poiStrings.take(3).joinToString(";")).apply()
        }

        @androidx.annotation.VisibleForTesting
        internal fun getHistoricalPois(): List<Triple<Double, Double, String>> = 
            prefs.getString("historical_pois", "")!!
                .split(";")
                .filter { it.isNotEmpty() }
                .mapNotNull { 
                    runCatching { 
                        val parts = it.split("|")
                        Triple(parts[1].toDouble(), parts[2].toDouble(), parts[0]) 
                    }.getOrNull() 
                }

        fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float { 
            val results = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, results)
            return results[0] 
        }

        /** Convert an ObservationEntity to a HourlyActualEntity keyed by its hour. */
        private fun observationToActual(obs: ObservationEntity, source: String): HourlyActualEntity {
            val hourKey = java.time.Instant.ofEpochMilli(obs.timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
                .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            return HourlyActualEntity(
                dateTime = hourKey,
                locationLat = obs.locationLat,
                locationLon = obs.locationLon,
                temperature = obs.temperature,
                condition = obs.condition,
                source = source,
                fetchedAt = obs.fetchedAt,
            )
        }

        /**
         * One-time seed: if hourly_actuals has no NWS data for this location but observations exist,
         * convert all existing observations to actuals. Runs once on first call after the feature launch.
         */
        private suspend fun seedActualsFromObservationsIfNeeded(latitude: Double, longitude: Double) {
            val oneMonthAgo = System.currentTimeMillis() - 2592000000L
            val existing = hourlyActualDao.getActualsInRange(
                startDateTime = "2000-01-01T00:00",
                endDateTime = "2099-12-31T23:00",
                source = com.weatherwidget.data.model.WeatherSource.NWS.id,
                lat = latitude,
                lon = longitude,
            )
            if (existing.isNotEmpty()) return // already seeded
            val observations = observationDao.getRecentObservations(oneMonthAgo)
            if (observations.isEmpty()) return
            val actuals = observations.map { observationToActual(it, com.weatherwidget.data.model.WeatherSource.NWS.id) }
            hourlyActualDao.insertAll(actuals)
            android.util.Log.i(TAG, "Seeded ${actuals.size} hourly actuals from existing observations")
        }

        suspend fun backfillNwsObservationsIfNeeded(latitude: Double, longitude: Double) {
            android.util.Log.d(TAG, "backfillNwsObservationsIfNeeded entered for ($latitude, $longitude)")
            seedActualsFromObservationsIfNeeded(latitude, longitude)
            val localZone = java.time.ZoneId.systemDefault()
            val now = java.time.ZonedDateTime.now(localZone)
            
            // Check for yesterday's data
            val yesterday = now.minusDays(1).toLocalDate()
            val startTsYesterday = yesterday.atStartOfDay(localZone).toInstant().toEpochMilli()
            val endTsYesterday = yesterday.plusDays(1).atStartOfDay(localZone).toInstant().toEpochMilli()
            
            val yesterdayObservations = observationDao.getObservationsInRange(startTsYesterday, endTsYesterday, latitude, longitude)
            val isYesterdayPopulated = yesterdayObservations.size >= 10
            
            // Also check for today's data density
            val today = now.toLocalDate()
            val startTsToday = today.atStartOfDay(localZone).toInstant().toEpochMilli()
            val endTsToday = now.toInstant().toEpochMilli()
            
            val todayObservations = observationDao.getObservationsInRange(startTsToday, endTsToday, latitude, longitude)
            val currentHour = now.hour
            val isTodayPopulated = when {
                currentHour < 2 -> true // Too early to judge today
                currentHour < 6 -> todayObservations.size >= 2
                currentHour < 12 -> todayObservations.size >= 4
                else -> todayObservations.size >= 8
            }

            android.util.Log.d(TAG, "History check: yesterdayCount=${yesterdayObservations.size}, todayCount=${todayObservations.size}, hour=$currentHour")
            
            if (isYesterdayPopulated && isTodayPopulated) {
                android.util.Log.d(TAG, "Skipping backfill: both yesterday and today already have sufficient data")
                return
            }
            
            android.util.Log.i(TAG, "Insufficient history (yesterdayPopulated=$isYesterdayPopulated, todayPopulated=$isTodayPopulated), backfilling last 48 hours")
            val gridPoint = runCatching { nwsApi.getGridPoint(latitude, longitude) }.getOrNull()
            if (gridPoint == null) {
                android.util.Log.e(TAG, "Failed to get grid point for ($latitude, $longitude)")
                return
            }
            
            val stations = getSortedObservationStations(gridPoint.observationStationsUrl ?: "")
            android.util.Log.d(TAG, "Found ${stations.size} stations to try")
            if (stations.isEmpty()) return
            
            // Fetch a wider window based on configuration to ensure we cover all of the required partials
            val startTimeStr = java.time.format.DateTimeFormatter.ISO_INSTANT.format(now.minusDays(WeatherConfig.NWS_BACKFILL_DAYS.toLong()).toInstant())
            val endTimeStr = java.time.format.DateTimeFormatter.ISO_INSTANT.format(now.toInstant())
            
            for (stationInfo in stations.take(3)) {
                android.util.Log.d(TAG, "Attempting backfill from station ${stationInfo.id}")
                try {
                    val observations = nwsApi.getObservations(stationInfo.id, startTimeStr, endTimeStr)
                    android.util.Log.d(TAG, "Station ${stationInfo.id} returned ${observations.size} observations")
                    if (observations.isNotEmpty()) {
                        val entities = observations.map { obs ->
                            com.weatherwidget.data.local.ObservationEntity(
                                stationId = stationInfo.id,
                                stationName = obs.stationName.ifEmpty { stationInfo.name },
                                timestamp = java.time.OffsetDateTime.parse(obs.timestamp).toInstant().toEpochMilli(),
                                temperature = (obs.temperatureCelsius * 1.8f) + 32f,
                                condition = obs.textDescription,
                                locationLat = latitude,
                                locationLon = longitude,
                                distanceKm = calculateDistance(latitude, longitude, stationInfo.lat, stationInfo.lon) / 1000f,
                                stationType = stationInfo.type.name
                            )
                        }
                        observationDao.insertAll(entities)
                        // Also write to hourly_actuals (bucketed to the nearest hour key)
                        val hourlyActuals = observations.mapNotNull { obs ->
                            val epochMs = runCatching {
                                java.time.OffsetDateTime.parse(obs.timestamp).toInstant().toEpochMilli()
                            }.getOrNull() ?: return@mapNotNull null
                            val hourKey = java.time.Instant.ofEpochMilli(epochMs)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDateTime()
                                .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                            HourlyActualEntity(
                                dateTime = hourKey,
                                locationLat = latitude,
                                locationLon = longitude,
                                temperature = (obs.temperatureCelsius * 1.8f) + 32f,
                                condition = obs.textDescription,
                                source = com.weatherwidget.data.model.WeatherSource.NWS.id,
                                fetchedAt = System.currentTimeMillis(),
                            )
                        }
                        if (hourlyActuals.isNotEmpty()) {
                            hourlyActualDao.insertAll(hourlyActuals)
                        }
                        android.util.Log.i(TAG, "Successfully backfilled ${entities.size} observations from ${stationInfo.id}")
                        break
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to backfill from ${stationInfo.id}: ${e.message}")
                }
            }
        }
    }

internal data class CurrentReadingPayload(
    val source: WeatherSource, 
    val temperature: Float, 
    val condition: String?, 
    val observedAt: Long?
)
