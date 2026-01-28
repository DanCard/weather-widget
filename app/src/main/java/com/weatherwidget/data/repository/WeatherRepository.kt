package com.weatherwidget.data.repository

import android.util.Log
import com.weatherwidget.data.local.WeatherDao
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WeatherRepository"

@Singleton
class WeatherRepository @Inject constructor(
    private val weatherDao: WeatherDao,
    private val nwsApi: NwsApi,
    private val openMeteoApi: OpenMeteoApi
) {
    companion object {
        private const val MONTH_IN_MILLIS = 30L * 24 * 60 * 60 * 1000
    }

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

            val weather = fetchFromApis(lat, lon, locationName)
            weatherDao.insertAll(weather)
            cleanOldData()
            Result.success(weather)
        } catch (e: Exception) {
            val cached = getCachedData(lat, lon)
            if (cached.isNotEmpty()) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    private suspend fun getCachedData(lat: Double, lon: Double): List<WeatherEntity> {
        val yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val nextWeek = LocalDate.now().plusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE)
        return weatherDao.getWeatherRange(yesterday, nextWeek, lat, lon)
    }

    private suspend fun fetchFromApis(
        lat: Double,
        lon: Double,
        locationName: String
    ): List<WeatherEntity> {
        return try {
            Log.d(TAG, "fetchFromApis: Trying NWS first")
            fetchFromNws(lat, lon, locationName)
        } catch (e: Exception) {
            Log.d(TAG, "fetchFromApis: NWS failed, trying Open-Meteo", e)
            fetchFromOpenMeteo(lat, lon, locationName)
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
                isActual = LocalDate.parse(date).isBefore(LocalDate.now())
            )
        }
    }

    private suspend fun fetchFromOpenMeteo(
        lat: Double,
        lon: Double,
        locationName: String
    ): List<WeatherEntity> {
        Log.d(TAG, "fetchFromOpenMeteo: Fetching for $lat, $lon")
        val forecast = openMeteoApi.getForecast(lat, lon)
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
                isActual = LocalDate.parse(daily.date).isBefore(LocalDate.now())
            )
        }
    }

    private suspend fun cleanOldData() {
        val cutoff = System.currentTimeMillis() - MONTH_IN_MILLIS
        weatherDao.deleteOldData(cutoff)
    }

    suspend fun getLatestLocation(): Pair<Double, Double>? {
        val latest = weatherDao.getLatestWeather()
        return latest?.let { it.locationLat to it.locationLon }
    }
}
