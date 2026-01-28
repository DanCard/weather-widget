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

        return GridPointInfo(
            gridId = properties["gridId"]?.jsonPrimitive?.content ?: "",
            gridX = properties["gridX"]?.jsonPrimitive?.content?.toInt() ?: 0,
            gridY = properties["gridY"]?.jsonPrimitive?.content?.toInt() ?: 0,
            forecastUrl = properties["forecast"]?.jsonPrimitive?.content ?: ""
        )
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
                temperature = obj["temperature"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                temperatureUnit = obj["temperatureUnit"]?.jsonPrimitive?.content ?: "F",
                shortForecast = obj["shortForecast"]?.jsonPrimitive?.content ?: "",
                isDaytime = obj["isDaytime"]?.jsonPrimitive?.content?.toBoolean() ?: true
            )
        }
    }

    data class GridPointInfo(
        val gridId: String,
        val gridX: Int,
        val gridY: Int,
        val forecastUrl: String
    )

    data class ForecastPeriod(
        val name: String,
        val temperature: Int,
        val temperatureUnit: String,
        val shortForecast: String,
        val isDaytime: Boolean
    )
}
