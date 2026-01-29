package com.weatherwidget.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.weatherwidget.data.ApiLogger
import com.weatherwidget.data.local.ForecastSnapshotDao
import com.weatherwidget.data.local.ForecastSnapshotEntity
import com.weatherwidget.data.local.WeatherDao
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.widget.ApiPreference
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private const val TAG = "WeatherRepository"

@Singleton
class WeatherRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val weatherDao: WeatherDao,
    private val forecastSnapshotDao: ForecastSnapshotDao,
    private val nwsApi: NwsApi,
    private val openMeteoApi: OpenMeteoApi,
    private val widgetStateManager: WidgetStateManager,
    private val apiLogger: ApiLogger
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
    }
    companion object {
        private const val MONTH_IN_MILLIS = 30L * 24 * 60 * 60 * 1000
        private const val SNAPSHOT_CUTOFF_HOUR = 20  // 8pm - don't save snapshots after this hour
    }

    // Toggle between APIs - alternate fairly between both
    private var useOpenMeteoFirst = false

    suspend fun getWeatherData(
        lat: Double,
        lon: Double,
        locationName: String,
        forceRefresh: Boolean = false
    ): Result<List<WeatherEntity>> {
        return try {
            if (!forceRefresh) {
                val cached = getCachedData(lat, lon)
                if (cached.isNotEmpty()) {
                    return Result.success(cached)
                }
            }

            val (displayWeather, _) = fetchFromBothApis(lat, lon, locationName)

            weatherDao.insertAll(displayWeather)
            cleanOldData()
            // Return from database to include previously cached data (e.g., yesterday from Open-Meteo)
            Result.success(getCachedData(lat, lon))
        } catch (e: Exception) {
            val cached = getCachedData(lat, lon)
            if (cached.isNotEmpty()) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    private fun shouldSaveSnapshot(): Boolean {
        val now = LocalTime.now()
        return now.hour < SNAPSHOT_CUTOFF_HOUR
    }

    private suspend fun saveForecastSnapshot(
        weather: List<WeatherEntity>,
        lat: Double,
        lon: Double,
        source: String
    ) {
        if (!shouldSaveSnapshot()) {
            Log.d(TAG, "Skipping snapshot save (after ${SNAPSHOT_CUTOFF_HOUR}:00)")
            return
        }

        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val tomorrowStr = today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Find tomorrow's forecast in the new weather data
        val tomorrowForecast = weather.find { it.date == tomorrowStr }

        if (tomorrowForecast != null) {
            // Save as 1-day-ahead forecast (forecasted today for tomorrow)
            val snapshot = ForecastSnapshotEntity(
                targetDate = tomorrowStr,
                forecastDate = todayStr,
                locationLat = lat,
                locationLon = lon,
                highTemp = tomorrowForecast.highTemp,
                lowTemp = tomorrowForecast.lowTemp,
                condition = tomorrowForecast.condition,
                source = source,
                fetchedAt = System.currentTimeMillis()
            )
            forecastSnapshotDao.insertSnapshot(snapshot)
            Log.d(TAG, "Saved forecast snapshot ($source): $todayStr forecast for $tomorrowStr - H:${snapshot.highTemp} L:${snapshot.lowTemp}")
        }
    }

    suspend fun getForecastForDate(
        targetDate: String,
        lat: Double,
        lon: Double
    ): ForecastSnapshotEntity? {
        return forecastSnapshotDao.getForecastForDate(targetDate, lat, lon)
    }

    suspend fun getForecastForDateBySource(
        targetDate: String,
        lat: Double,
        lon: Double,
        source: String
    ): ForecastSnapshotEntity? {
        // Get all snapshots for this date and filter by source
        val snapshots = forecastSnapshotDao.getForecastsInRange(targetDate, targetDate, lat, lon)
        return snapshots.find { it.source == source }
    }

    suspend fun getForecastsInRange(
        startDate: String,
        endDate: String,
        lat: Double,
        lon: Double
    ): List<ForecastSnapshotEntity> {
        return forecastSnapshotDao.getForecastsInRange(startDate, endDate, lat, lon)
    }

    suspend fun getWeatherRange(
        startDate: String,
        endDate: String,
        lat: Double,
        lon: Double
    ): List<WeatherEntity> {
        return weatherDao.getWeatherRange(startDate, endDate, lat, lon)
    }

    private suspend fun getCachedData(lat: Double, lon: Double): List<WeatherEntity> {
        val yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val twoWeeks = LocalDate.now().plusDays(14).format(DateTimeFormatter.ISO_LOCAL_DATE)
        return weatherDao.getWeatherRange(yesterday, twoWeeks, lat, lon)
    }

    /**
     * Fetches weather from both APIs concurrently and saves snapshots from both sources.
     * Returns the preferred API's data for display, and both results for snapshot saving.
     */
    private suspend fun fetchFromBothApis(
        lat: Double,
        lon: Double,
        locationName: String
    ): Pair<List<WeatherEntity>, List<WeatherEntity>?> = coroutineScope {
        val apiPreference = widgetStateManager.getApiPreference()

        // Try both APIs concurrently
        val nwsDeferred = async {
            try {
                val startTime = System.currentTimeMillis()
                val result = fetchFromNws(lat, lon, locationName)
                val duration = System.currentTimeMillis() - startTime
                apiLogger.logApiCall("NWS", true, null, locationName, duration)
                Log.d(TAG, "fetchFromBothApis: NWS succeeded")
                result to null
            } catch (e: Exception) {
                Log.d(TAG, "fetchFromBothApis: NWS failed: ${e.message}")
                apiLogger.logApiCall("NWS", false, e.message ?: "Unknown error", locationName)
                null to e
            }
        }

        val meteoDeferred = async {
            try {
                val startTime = System.currentTimeMillis()
                val result = fetchFromOpenMeteo(lat, lon, locationName, days = 14)
                val duration = System.currentTimeMillis() - startTime
                apiLogger.logApiCall("Open-Meteo", true, null, locationName, duration)
                Log.d(TAG, "fetchFromBothApis: Open-Meteo succeeded")
                result to null
            } catch (e: Exception) {
                Log.d(TAG, "fetchFromBothApis: Open-Meteo failed: ${e.message}")
                apiLogger.logApiCall("Open-Meteo", false, e.message ?: "Unknown error", locationName)
                null to e
            }
        }

        val nwsResult = nwsDeferred.await()
        val meteoResult = meteoDeferred.await()

        // Save snapshots from both APIs (if available and before cutoff time)
        nwsResult.first?.let { saveForecastSnapshot(it, lat, lon, "NWS") }
        meteoResult.first?.let { saveForecastSnapshot(it, lat, lon, "OPEN_METEO") }

        // Determine which to use for display based on preference
        val tryOpenMeteoFirst = when (apiPreference) {
            ApiPreference.PREFER_OPENMETEO -> true
            ApiPreference.PREFER_NWS -> false
            ApiPreference.ALTERNATE -> {
                val result = useOpenMeteoFirst
                useOpenMeteoFirst = !useOpenMeteoFirst
                result
            }
        }

        val (primaryResult, secondaryResult) = if (tryOpenMeteoFirst) {
            meteoResult to nwsResult
        } else {
            nwsResult to meteoResult
        }

        // Return preferred API's data, or fallback to the other
        val primaryApiName = if (tryOpenMeteoFirst) "Open-Meteo" else "NWS"
        val secondaryApiName = if (tryOpenMeteoFirst) "NWS" else "Open-Meteo"

        when {
            primaryResult.first != null -> {
                prefs.edit().putString("last_api_source", primaryApiName).apply()
                primaryResult.first!! to secondaryResult.first
            }
            secondaryResult.first != null -> {
                Log.d(TAG, "fetchFromBothApis: Using fallback API for display")
                prefs.edit().putString("last_api_source", secondaryApiName).apply()
                secondaryResult.first!! to null
            }
            else -> {
                Log.e(TAG, "fetchFromBothApis: Both APIs failed")
                throw primaryResult.second ?: Exception("Both APIs failed")
            }
        }
    }

    private suspend fun fetchFromNws(
        lat: Double,
        lon: Double,
        locationName: String
    ): List<WeatherEntity> {
        val gridPoint = nwsApi.getGridPoint(lat, lon)
        val forecast = nwsApi.getForecast(gridPoint)
        Log.d(TAG, "fetchFromNws: Got ${forecast.size} periods")

        val weatherByDate = mutableMapOf<String, Pair<Int?, Int?>>()
        val conditionByDate = mutableMapOf<String, String>()
        val today = LocalDate.now()

        // NWS returns periods in pairs: day/night for each day
        // Calculate date based on period index
        forecast.forEachIndexed { index, period ->
            // Each day has 2 periods (day + night), so day offset = index / 2
            val dayOffset = index / 2L
            val date = today.plusDays(dayOffset).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val current = weatherByDate[date] ?: (null to null)

            Log.d(TAG, "  Period $index: ${period.name} isDaytime=${period.isDaytime} temp=${period.temperature} -> date=$date")

            if (period.isDaytime) {
                weatherByDate[date] = period.temperature to current.second
                conditionByDate[date] = period.shortForecast
            } else {
                weatherByDate[date] = current.first to period.temperature
            }
        }

        Log.d(TAG, "fetchFromNws: Parsed ${weatherByDate.size} days")

        return weatherByDate.map { (date, temps) ->
            WeatherEntity(
                date = date,
                locationLat = lat,
                locationLon = lon,
                locationName = locationName,
                highTemp = temps.first ?: 0,
                lowTemp = temps.second ?: 0,
                currentTemp = null,
                condition = conditionByDate[date] ?: "Unknown",
                isActual = LocalDate.parse(date).isBefore(LocalDate.now()),
                source = "NWS"
            )
        }
    }

    private suspend fun fetchFromOpenMeteo(
        lat: Double,
        lon: Double,
        locationName: String,
        days: Int = 7
    ): List<WeatherEntity> {
        Log.d(TAG, "fetchFromOpenMeteo: Fetching for $lat, $lon (days=$days)")
        val forecast = openMeteoApi.getForecast(lat, lon, days)
        Log.d(TAG, "fetchFromOpenMeteo: Got ${forecast.daily.size} days from API")
        forecast.daily.forEach { d ->
            Log.d(TAG, "  API day: ${d.date} H=${d.highTemp} L=${d.lowTemp}")
        }
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        return forecast.daily.map { daily ->
            WeatherEntity(
                date = daily.date,
                locationLat = lat,
                locationLon = lon,
                locationName = locationName,
                highTemp = daily.highTemp,
                lowTemp = daily.lowTemp,
                currentTemp = if (daily.date == today) forecast.currentTemp else null,
                condition = openMeteoApi.weatherCodeToCondition(daily.weatherCode),
                isActual = LocalDate.parse(daily.date).isBefore(LocalDate.now()),
                source = "Open-Meteo"
            )
        }
    }

    private suspend fun cleanOldData() {
        val cutoff = System.currentTimeMillis() - MONTH_IN_MILLIS
        weatherDao.deleteOldData(cutoff)
        forecastSnapshotDao.deleteOldSnapshots(cutoff)
    }

    suspend fun getLatestLocation(): Pair<Double, Double>? {
        val latest = weatherDao.getLatestWeather()
        return latest?.let { it.locationLat to it.locationLon }
    }
}
