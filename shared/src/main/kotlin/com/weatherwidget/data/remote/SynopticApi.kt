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

        /**
         * Pure half of [fetchSynopticObservations], HTTP-free so fixture JSON can drive the outcome
         * matrix in tests. Failed on API-level rejection (RESPONSE_CODE≠1) or malformed payload;
         * NoData when the response is well-formed but carries no temperature-bearing observations.
         */
        internal fun parseSynopticTimeseries(
            json: Json,
            response: String,
            stationId: String,
            stationNameFallback: String,
        ): FetchOutcome<List<NwsApi.Observation>> {
            return try {
                val root = json.parseToJsonElement(response).jsonObject
                val summaryObj = root["SUMMARY"]?.jsonObject
                val responseCode = summaryObj?.get("RESPONSE_CODE")?.jsonPrimitive?.intOrNull
                if (responseCode != 1) {
                    val message = summaryObj?.get("RESPONSE_MESSAGE")?.jsonPrimitive?.contentOrNull
                    Log.w(TAG, "Synoptic request failed for $stationId: $message")
                    return FetchOutcome.Failed("synoptic: $message")
                }

                // A successful response without station/observation structures is Synoptic's way of
                // saying the station has nothing in the window — definitive NoData, not a failure.
                val stationArray = root["STATION"]?.jsonArray
                val firstStation = stationArray?.firstOrNull()?.jsonObject ?: return FetchOutcome.NoData
                val obsObj = firstStation["OBSERVATIONS"]?.jsonObject ?: return FetchOutcome.NoData

                val dateTimeArray = obsObj["date_time"]?.jsonArray ?: return FetchOutcome.NoData
                val airTempArray = obsObj["air_temp_set_1"]?.jsonArray
                val weatherSummaryArray = obsObj["weather_summary_set_1d"]?.jsonArray
                val weatherCondArray = obsObj["weather_condition_set_1d"]?.jsonArray
                // Parallel to air_temp_set_1: null = passed QC, array of check IDs = flagged
                // (e.g. [105] = SynopticLabs Spatial Value Check). Only present when the request
                // asks for qc_flags; absent QC block means nothing was flagged.
                val airTempQcArray = firstStation["QC"]?.jsonObject?.get("air_temp_set_1")?.jsonArray

                val stationName = firstStation["NAME"]?.jsonPrimitive?.content ?: stationNameFallback
                val observationList = mutableListOf<NwsApi.Observation>()
                val qcDropped = mutableListOf<String>()

                for (i in 0 until dateTimeArray.size) {
                    val rawDateTimeStr = dateTimeArray[i].jsonPrimitive.content
                    val dateTimeStr = parseTimestampToIsoString(rawDateTimeStr)
                    val tempC = airTempArray?.getOrNull(i)?.jsonPrimitive?.doubleOrNull?.toFloat()
                        ?: continue // Skip observation if temperature is missing

                    val qcChecks = airTempQcArray?.getOrNull(i) as? JsonArray
                    val qcFailed = !qcChecks.isNullOrEmpty()
                    if (qcFailed) {
                        qcDropped.add("$dateTimeStr temp=$tempC checks=${qcChecks!!.joinToString(",") { it.jsonPrimitive.content }}")
                    }

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
                            precipLastHourMm = null,
                            qcFailed = qcFailed,
                        )
                    )
                }
                if (qcDropped.isNotEmpty()) {
                    Log.w(TAG, "Synoptic $stationId: ${qcDropped.size} QC-flagged reading(s) marked unusable: $qcDropped")
                }
                if (observationList.isEmpty()) FetchOutcome.NoData else FetchOutcome.Success(observationList)
            } catch (e: Exception) {
                Log.w(TAG, "Synoptic parse for $stationId failed: $e")
                FetchOutcome.Failed("parse: ${e.message}")
            }
        }
    }

    /**
     * Recent observations from Synoptic's timeseries endpoint. [FetchOutcome.NoData] means the
     * request succeeded but the station has no temperature-bearing observations in the window;
     * [FetchOutcome.Failed] covers transport errors and API-level rejections (RESPONSE_CODE≠1).
     * A Success list is never empty.
     */
    suspend fun fetchSynopticObservations(
        stationId: String,
        recentMinutes: Long,
        stationNameFallback: String = ""
    ): FetchOutcome<List<NwsApi.Observation>> {
        val response: String = try {
            val token = "7c76618b66c74aee913bdbae4b448bdd"
            val url = "https://api.synopticdata.com/v2/stations/timeseries"

            httpClient.get(url) {
                parameter("STID", stationId)
                parameter("recent", recentMinutes)
                parameter("token", token)
                parameter("obtimezone", "utc")
                // Ask Synoptic to run its full QC suite and return per-observation flags —
                // qc_checks=all includes the spatial (neighbor-comparison) check that catches
                // physically implausible readings the basic range check passes (KPAO 2026-07-13:
                // a 10°C ob between 22–23°C neighbors was flagged only by check 105).
                parameter("qc", "on")
                parameter("qc_checks", "all")
                parameter("qc_flags", "on")
                header("Referer", "https://www.weather.gov/wrh/timeseries?site=$stationId")
                header("Origin", "https://www.weather.gov")
            }.body()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Synoptic fallback for $stationId fetch failed: $e")
            return FetchOutcome.failed(e)
        }
        return parseSynopticTimeseries(json, response, stationId, stationNameFallback)
    }

}
