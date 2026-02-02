package com.weatherwidget.data.remote

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class NwsApi @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) {
    companion object {
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

            if (tempValue != null) {
                Observation(
                    timestamp = timestamp,
                    temperatureCelsius = tempValue
                )
            } else {
                null
            }
        }
    }

    suspend fun getForecast(gridPoint: GridPointInfo): List<ForecastPeriod> {
        val response: String = httpClient.get(gridPoint.forecastUrl) {
            header("User-Agent", USER_AGENT)
            header("Accept", "application/json")
        }.body()

        val jsonObj = json.parseToJsonElement(response).jsonObject
        val periods = jsonObj["properties"]?.jsonObject?.get("periods")?.jsonArray
            ?: return emptyList()

        return periods.mapNotNull { period ->
            val obj = period.jsonObject
            ForecastPeriod(
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                startTime = obj["startTime"]?.jsonPrimitive?.content ?: "",
                temperature = obj["temperature"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                temperatureUnit = obj["temperatureUnit"]?.jsonPrimitive?.content ?: "F",
                shortForecast = obj["shortForecast"]?.jsonPrimitive?.content ?: "",
                isDaytime = obj["isDaytime"]?.jsonPrimitive?.content?.toBoolean() ?: true
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
            val temperature = obj["temperature"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
            val temperatureUnit = obj["temperatureUnit"]?.jsonPrimitive?.content ?: "F"

            // Convert to Fahrenheit if needed (NWS usually returns F)
            val tempF = if (temperatureUnit == "C") {
                (temperature * 9 / 5) + 32
            } else {
                temperature
            }

            HourlyForecastPeriod(
                startTime = startTime,
                temperature = tempF
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
        val temperatureCelsius: Double
    )

    data class HourlyForecastPeriod(
        val startTime: String,  // ISO 8601 format: "2026-02-01T10:00:00-08:00"
        val temperature: Int    // Fahrenheit
    )
}
