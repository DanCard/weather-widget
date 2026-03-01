package com.weatherwidget.data.repository

import android.content.Context
import android.location.Location
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.CurrentTempDao
import com.weatherwidget.data.local.CurrentTempEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.WeatherApi
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
        private val hourlyForecastDao: HourlyForecastDao,
        private val appLogDao: AppLogDao,
        private val nwsApi: NwsApi,
        private val openMeteoApi: OpenMeteoApi,
        private val weatherApi: WeatherApi,
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
                    
                    val targetSources = (source?.let { listOf(it) } ?: widgetStateManager.getVisibleSourcesOrder())
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
                else -> null
            }

        private suspend fun fetchOpenMeteoCurrent(latitude: Double, longitude: Double): CurrentReadingPayload? = coroutineScope {
            val pointsOfInterest = getPointsOfInterest(latitude, longitude)
            val deferredReadings = pointsOfInterest.mapIndexed { index, point ->
                async {
                    val reading = runCatching { openMeteoApi.getCurrent(point.first, point.second) }.getOrNull()
                    if (reading != null) {
                        val condition = reading.weatherCode?.let { openMeteoApi.weatherCodeToCondition(it) } ?: "Unknown"
                        val stationId = if (point.third == "Current") "OPEN_METEO_MAIN" else "OPEN_METEO_$index"
                        observationDao.insertAll(listOf(
                            ObservationEntity(
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
                        ))
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
                        observationDao.insertAll(listOf(
                            ObservationEntity(
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
                        ))
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
            
            val deferredObservations = stations.take(MAX_RETRIES).map { stationInfo ->
                async {
                    val observation = runCatching { nwsApi.getLatestObservationDetailed(stationInfo.id) }.getOrNull()
                    if (observation != null) {
                        observationDao.insertAll(listOf(
                            ObservationEntity(
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
                        ))
                    }
                    observation
                }
            }.map { it.await() }
            
            deferredObservations.firstNotNullOfOrNull { it }?.let { observation ->
                CurrentReadingPayload(
                    WeatherSource.NWS, 
                    (observation.temperatureCelsius * 1.8f) + 32f, 
                    observation.textDescription, 
                    OffsetDateTime.parse(observation.timestamp).toInstant().toEpochMilli()
                ) 
            }
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
                return cachedStationsString.split("|").map { 
                    val parts = it.split("\t")
                    NwsApi.StationInfo(parts[0], parts[1], parts[2].toDouble(), parts[3].toDouble())
                }
            }
            
            val fetchedStations = runCatching { nwsApi.getObservationStations(stationsUrl) }.getOrDefault(emptyList())
            if (fetchedStations.isNotEmpty()) {
                prefs.edit()
                    .putString(stationsKey, fetchedStations.joinToString("|") { "${it.id}\t${it.name}\t${it.lat}\t${it.lon}" })
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
    }

internal data class CurrentReadingPayload(
    val source: WeatherSource, 
    val temperature: Float, 
    val condition: String?, 
    val observedAt: Long?
)
