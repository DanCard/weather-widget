package com.weatherwidget.data.repository

import android.content.Context
import android.location.Location
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyExtremeDao
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
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
        private val observationDao: ObservationDao,
        private val hourlyForecastDao: HourlyForecastDao,
        private val appLogDao: AppLogDao,
        private val nwsApi: NwsApi,
        private val openMeteoApi: OpenMeteoApi,
        private val weatherApi: WeatherApi,
        private val silurianApi: SilurianApi,
        private val widgetStateManager: WidgetStateManager,
        private val temperatureInterpolator: TemperatureInterpolator,
        private val dailyExtremeDao: DailyExtremeDao,
        private val observationRepository: ObservationRepository,
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
                            fetchFromSource(targetSource, latitude, longitude) ?: return@forEach
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
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
                WeatherSource.NWS -> observationRepository.fetchNwsCurrent(latitude, longitude)
                WeatherSource.SILURIAN -> fetchSilurianCurrent(latitude, longitude)
                else -> null
            }

        private suspend fun fetchSilurianCurrent(latitude: Double, longitude: Double): CurrentReadingPayload? = coroutineScope {
            val pointsOfInterest = getPointsOfInterest(latitude, longitude)
            val deferredReadings = pointsOfInterest.mapIndexed { index, point ->
                async {
                    val reading = try {
                        silurianApi.getForecast(point.first, point.second, 1)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
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
                            locationLon = longitude,
                            api = WeatherSource.SILURIAN.id,
                        )
                        observationDao.insertAll(listOf(obsEntity))
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
                    val reading = try {
                        openMeteoApi.getCurrent(point.first, point.second)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
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
                            "OFFICIAL",
                            api = WeatherSource.OPEN_METEO.id,
                        )
                        observationDao.insertAll(listOf(obsEntity))
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
                    val reading = try {
                        weatherApi.getCurrent(point.first, point.second)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
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
                            "OFFICIAL",
                            api = WeatherSource.WEATHER_API.id,
                        )
                        observationDao.insertAll(listOf(obsEntity))
                    }
                    reading
                }
            }.map { it.await() }

            deferredReadings.firstNotNullOfOrNull { it }?.let { reading ->
                CurrentReadingPayload(WeatherSource.WEATHER_API, reading.temperature, reading.condition, reading.observedAt) 
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
            time: LocalDateTime = LocalDateTime.now(),
        ): Float? {
            val zoneId = ZoneId.systemDefault()
            val startMs = time.minusHours(3).truncatedTo(java.time.temporal.ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()
            val endMs = time.plusHours(3).truncatedTo(java.time.temporal.ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()
            val hourlyForecasts = hourlyForecastDao.getHourlyForecasts(startMs, endMs, latitude, longitude)

            return if (hourlyForecasts.isEmpty()) null else temperatureInterpolator.getInterpolatedTemperature(hourlyForecasts, time)
        }

        suspend fun getNextInterpolationUpdateTime(
            latitude: Double,
            longitude: Double,
            time: LocalDateTime = LocalDateTime.now(),
        ): LocalDateTime {
            val zoneId = ZoneId.systemDefault()
            val currentHourMs = time.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .atZone(zoneId).toInstant().toEpochMilli()
            val nextHourMs = time.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1)
                .atZone(zoneId).toInstant().toEpochMilli()
            val forecasts = hourlyForecastDao.getHourlyForecasts(currentHourMs, nextHourMs, latitude, longitude)
            
            val tempDiff = if (forecasts.size >= 2) (forecasts[1].temperature - forecasts[0].temperature).toInt() else 0
            return temperatureInterpolator.getNextUpdateTime(time, tempDiff)
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
