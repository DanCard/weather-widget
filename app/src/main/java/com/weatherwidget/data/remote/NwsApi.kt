package com.weatherwidget.data.remote

import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import kotlin.math.roundToInt

class NwsApi @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) {
    companion object {
        private const val TAG = "NwsApi"
        private const val BASE_URL = "https://api.weather.gov"
        private const val USER_AGENT = "WeatherWidget/1.0 (contact@weatherwidget.app)"
    }

    suspend fun getGridPoint(lat: Double, lon: Double): GridPointInfo {
        val response: String = httpClient.get("$BASE_URL/points/$lat,$lon") {
            header("User-Agent", USER_AGENT)
            header("Accept", "application/json")
        }.body()

        val jsonObj = json.parseToJsonElement(response).jsonObject
        val properties = jsonObj["properties"]?.jsonObject
            ?: throw Exception("Invalid NWS response")

        // Extract observation stations URL
        val observationStationsUrl = properties["observationStations"]?.jsonPrimitive?.content

        return GridPointInfo(
            gridId = properties["gridId"]?.jsonPrimitive?.content ?: "",
            gridX = properties["gridX"]?.jsonPrimitive?.content?.toInt() ?: 0,
            gridY = properties["gridY"]?.jsonPrimitive?.content?.toInt() ?: 0,
            forecastUrl = properties["forecast"]?.jsonPrimitive?.content ?: "",
            observationStationsUrl = observationStationsUrl
        )
    }

    suspend fun getObservationStations(stationsUrl: String): List<String> {
        val response: String = httpClient.get(stationsUrl) {
            header("User-Agent", USER_AGENT)
            header("Accept", "application/json")
        }.body()

        val jsonObj = json.parseToJsonElement(response).jsonObject
        val features = jsonObj["features"]?.jsonArray ?: return emptyList()

        return features.mapNotNull { feature ->
            feature.jsonObject["properties"]?.jsonObject?.get("stationIdentifier")?.jsonPrimitive?.content
        }
    }

    suspend fun getObservations(stationId: String, start: String, end: String): List<Observation> {
        val response: String = httpClient.get("$BASE_URL/stations/$stationId/observations") {
            header("User-Agent", USER_AGENT)
            header("Accept", "application/json")
            parameter("start", start)
            parameter("end", end)
        }.body()

        val jsonObj = json.parseToJsonElement(response).jsonObject
        val features = jsonObj["features"]?.jsonArray ?: return emptyList()

        return features.mapNotNull { feature ->
            val props = feature.jsonObject["properties"]?.jsonObject ?: return@mapNotNull null
            val timestamp = props["timestamp"]?.jsonPrimitive?.content ?: return@mapNotNull null

            // Temperature is in a value object with unitCode
            val tempObj = props["temperature"]?.jsonObject
            val tempValue = tempObj?.get("value")?.jsonPrimitive?.content?.toDoubleOrNull()
            
            val textDescription = props["textDescription"]?.jsonPrimitive?.content ?: "Unknown"

            if (tempValue != null) {
                Observation(
                    timestamp = timestamp,
                    temperatureCelsius = tempValue.toFloat(),
                    textDescription = textDescription
                )
            } else {
                null
            }
        }
    }

    suspend fun getForecast(gridPoint: GridPointInfo): List<ForecastPeriod> {
        val fetchStartedAt = System.currentTimeMillis()
        val response: String = httpClient.get(gridPoint.forecastUrl) {
            header("User-Agent", USER_AGENT)
            header("Accept", "application/json")
        }.body()

        val jsonObj = json.parseToJsonElement(response).jsonObject
        val properties = jsonObj["properties"]?.jsonObject
        val updated = properties?.get("updated")?.jsonPrimitive?.content
        val generatedAt = properties?.get("generatedAt")?.jsonPrimitive?.content
        val periods = properties?.get("periods")?.jsonArray
            ?: return emptyList()

        Log.i(
            TAG,
            "getForecast: url=${gridPoint.forecastUrl}, fetchedAt=$fetchStartedAt, updated=$updated, generatedAt=$generatedAt, periodCount=${periods.size}"
        )

        return periods.mapIndexedNotNull { index, period ->
            val obj = period.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: ""
            val startTime = obj["startTime"]?.jsonPrimitive?.content ?: ""
            val endTime = obj["endTime"]?.jsonPrimitive?.content ?: ""
            val tempRaw = obj["temperature"]?.jsonPrimitive?.content
            val temperature = tempRaw?.toDoubleOrNull()?.roundToInt() ?: 0
            val temperatureUnit = obj["temperatureUnit"]?.jsonPrimitive?.content ?: "F"
            val shortForecast = obj["shortForecast"]?.jsonPrimitive?.content ?: ""
            val isDaytime = obj["isDaytime"]?.jsonPrimitive?.content?.toBoolean() ?: true

            Log.d(
                TAG,
                "getForecast[$index]: name=$name start=$startTime end=$endTime tempRaw=$tempRaw tempRounded=$temperature unit=$temperatureUnit isDaytime=$isDaytime short=$shortForecast"
            )

            ForecastPeriod(
                name = name,
                startTime = startTime,
                temperature = temperature,
                temperatureUnit = temperatureUnit,
                shortForecast = shortForecast,
                isDaytime = isDaytime
            )
        }
    }

    suspend fun getHourlyForecast(gridPoint: GridPointInfo): List<HourlyForecastPeriod> {
        val url = "$BASE_URL/gridpoints/${gridPoint.gridId}/${gridPoint.gridX},${gridPoint.gridY}/forecast/hourly"
        val response: String = httpClient.get(url) {
            header("User-Agent", USER_AGENT)
            header("Accept", "application/json")
        }.body()

        val jsonObj = json.parseToJsonElement(response).jsonObject
        val periods = jsonObj["properties"]?.jsonObject?.get("periods")?.jsonArray
            ?: return emptyList()

        return periods.mapNotNull { period ->
            val obj = period.jsonObject
            val startTime = obj["startTime"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val temperature = obj["temperature"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val temperatureUnit = obj["temperatureUnit"]?.jsonPrimitive?.content ?: "F"
            val shortForecast = obj["shortForecast"]?.jsonPrimitive?.content ?: "Unknown"

            // Convert to Fahrenheit if needed (NWS usually returns F)
            val tempF = if (temperatureUnit == "C") {
                (temperature.toFloat() * 1.8f) + 32f
            } else {
                temperature.toFloat()
            }

            HourlyForecastPeriod(
                startTime = startTime,
                temperature = tempF,
                shortForecast = shortForecast
            )
        }
    }

    data class GridPointInfo(
        val gridId: String,
        val gridX: Int,
        val gridY: Int,
        val forecastUrl: String,
        val observationStationsUrl: String? = null
    )

    data class ForecastPeriod(
        val name: String,
        val startTime: String,
        val temperature: Int,
        val temperatureUnit: String,
        val shortForecast: String,
        val isDaytime: Boolean
    )

    data class Observation(
        val timestamp: String,
        val temperatureCelsius: Float,
        val textDescription: String
    )

    data class HourlyForecastPeriod(
        val startTime: String,  // ISO 8601 format: "2026-02-01T10:00:00-08:00"
        val temperature: Float,   // Fahrenheit
        val shortForecast: String
    )
}
