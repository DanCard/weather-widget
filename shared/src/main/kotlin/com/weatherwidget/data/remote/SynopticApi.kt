package com.weatherwidget.data.remote

import com.weatherwidget.shared.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.*
import javax.inject.Inject

class SynopticApi @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    companion object {
        private const val TAG = "SynopticApi"

        fun parseTimestampToIsoString(ts: String): String {
            if (ts.endsWith("Z") || ts.endsWith("z")) return ts
            val lastPlus = ts.lastIndexOf('+')
            val lastMinus = ts.lastIndexOf('-')
            val offsetIdx = if (lastPlus > lastMinus) lastPlus else lastMinus
            if (offsetIdx > 0 && ts.length == offsetIdx + 5 && ts[offsetIdx + 2] != ':') {
                return ts.substring(0, offsetIdx + 3) + ":" + ts.substring(offsetIdx + 3)
            }
            return ts
        }
    }

    suspend fun fetchSynopticObservations(
        stationId: String,
        recentMinutes: Long,
        stationNameFallback: String = ""
    ): List<NwsApi.Observation>? {
        return try {
            val token = "7c76618b66c74aee913bdbae4b448bdd"
            val url = "https://api.synopticdata.com/v2/stations/timeseries"

            val response: String = httpClient.get(url) {
                parameter("STID", stationId)
                parameter("recent", recentMinutes)
                parameter("token", token)
                parameter("obtimezone", "utc")
                header("Referer", "https://www.weather.gov/wrh/timeseries?site=$stationId")
                header("Origin", "https://www.weather.gov")
            }.body()

            val root = json.parseToJsonElement(response).jsonObject
            val summaryObj = root["SUMMARY"]?.jsonObject
            val responseCode = summaryObj?.get("RESPONSE_CODE")?.jsonPrimitive?.intOrNull
            if (responseCode != 1) {
                val message = summaryObj?.get("RESPONSE_MESSAGE")?.jsonPrimitive?.contentOrNull
                Log.w(TAG, "Synoptic request failed for $stationId: $message")
                return null
            }

            val stationArray = root["STATION"]?.jsonArray
            val firstStation = stationArray?.firstOrNull()?.jsonObject ?: return null
            val obsObj = firstStation["OBSERVATIONS"]?.jsonObject ?: return null

            val dateTimeArray = obsObj["date_time"]?.jsonArray ?: return null
            val airTempArray = obsObj["air_temp_set_1"]?.jsonArray
            val weatherSummaryArray = obsObj["weather_summary_set_1d"]?.jsonArray
            val weatherCondArray = obsObj["weather_condition_set_1d"]?.jsonArray

            val stationName = firstStation["NAME"]?.jsonPrimitive?.content ?: stationNameFallback
            val observationList = mutableListOf<NwsApi.Observation>()

            for (i in 0 until dateTimeArray.size) {
                val rawDateTimeStr = dateTimeArray[i].jsonPrimitive.content
                val dateTimeStr = parseTimestampToIsoString(rawDateTimeStr)
                val tempC = airTempArray?.getOrNull(i)?.jsonPrimitive?.doubleOrNull?.toFloat()
                    ?: continue // Skip observation if temperature is missing
                
                val summary = weatherSummaryArray?.getOrNull(i)?.jsonPrimitive?.contentOrNull
                    ?: weatherCondArray?.getOrNull(i)?.jsonPrimitive?.contentOrNull
                    ?: "Unknown"

                observationList.add(
                    NwsApi.Observation(
                        timestamp = dateTimeStr,
                        temperatureCelsius = tempC,
                        textDescription = summary,
                        stationName = stationName,
                        maxTempLast24hCelsius = null,
                        minTempLast24hCelsius = null,
                        precipLastHourMm = null
                    )
                )
            }
            observationList
        } catch (e: Exception) {
            Log.w(TAG, "Synoptic fallback for $stationId fetch failed: $e")
            null
        }
    }
}
