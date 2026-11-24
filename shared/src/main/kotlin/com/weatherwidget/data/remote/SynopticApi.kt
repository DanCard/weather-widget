package com.weatherwidget.data.remote

import com.weatherwidget.data.model.StationType
import com.weatherwidget.shared.observations.MetarRawSkyParser
import com.weatherwidget.shared.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.*

/**
 * Synoptic Data / MesoWest observations, used as the NWS web fallback.
 *
 * [token] is a Synoptic **token**, not an API key — the data endpoints reject a raw key outright
 * ("Be sure to use a token generated from your API Key, and not the key itself"). Tokens are minted
 * from the key at `https://api.synopticdata.com/v2/auth?apikey=<KEY>` and carry no expiry, so the
 * app stores the token and never holds the key. That also means a leaked build exposes a revocable
 * token rather than the account credential.
 *
 * [mesoTokenApi] retrieves and caches the public browser token used by weather.gov's station viewer.
 * An optional [tokenProvider] can also supply a test token directly.
 */
class SynopticApi(
    private val httpClient: HttpClient,
    private val json: Json,
    private val mesoTokenApi: MesoTokenApi? = null,
    private val tokenProvider: () -> String? = { null },
) {
    constructor(
        httpClient: HttpClient,
        json: Json,
        tokenProvider: () -> String?,
    ) : this(httpClient, json, mesoTokenApi = null, tokenProvider = tokenProvider)

    /** True when the automatic weather.gov token source or a configured test token is available. */
    val isConfigured: Boolean get() = mesoTokenApi != null || !tokenProvider().isNullOrBlank()

    private suspend fun effectiveToken(forceRefresh: Boolean = false): String? =
        mesoTokenApi?.token(forceRefresh) ?: tokenProvider()?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "SynopticApi"

        /**
         * One `STATION` entry's observations. Extracted so the single-station web fallback and the
         * multi-station radius feed cannot drift on QC handling, the METAR sky parse, or the
         * missing-temperature rule — three things that were each a bug on this path before.
         */
        internal fun parseStationObservations(
            station: JsonObject,
            stationNameFallback: String,
        ): List<NwsApi.Observation> {
            val obsObj = station["OBSERVATIONS"]?.jsonObject ?: return emptyList()
            val dateTimeArray = obsObj["date_time"]?.jsonArray ?: return emptyList()
            val airTempArray = obsObj["air_temp_set_1"]?.jsonArray
            val weatherSummaryArray = obsObj["weather_summary_set_1d"]?.jsonArray
            val weatherCondArray = obsObj["weather_condition_set_1d"]?.jsonArray
            val metarArray = obsObj["metar_set_1"]?.jsonArray
            val cloudLayerArrays = (1..3).mapNotNull { layerIndex ->
                obsObj["cloud_layer_${layerIndex}_set_1d"]?.jsonArray
            }
            // Parallel to air_temp_set_1: null = passed QC, array of check IDs = flagged
            // (e.g. [105] = SynopticLabs Spatial Value Check).
            val airTempQcArray = station["QC"]?.jsonObject?.get("air_temp_set_1")?.jsonArray
            // Blank counts as missing, as it does for the radius StationInfo: a " " name showed nothing.
            val stationName = station["NAME"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: stationNameFallback

            val out = mutableListOf<NwsApi.Observation>()
            for (i in 0 until dateTimeArray.size) {
                val dateTimeStr = parseTimestampToIsoString(dateTimeArray[i].jsonPrimitive.content)
                // Skip rather than default: a station reporting only humidity is not a temperature
                // observation, and storing 0 C would poison the blend far worse than a missing row.
                val tempC = airTempArray?.getOrNull(i)?.jsonPrimitive?.doubleOrNull?.toFloat() ?: continue

                val qcChecks = airTempQcArray?.getOrNull(i) as? JsonArray
                val summary = weatherSummaryArray?.getOrNull(i)?.jsonPrimitive?.contentOrNull
                    ?: weatherCondArray?.getOrNull(i)?.jsonPrimitive?.contentOrNull
                    // Blank, not a placeholder: a RAWS station reports no condition, and the cards
                    // and NwsBlend treat "" as "not reported" while "Unknown" would be shown as data.
                    ?: ""
                val rawMetar = metarArray?.getOrNull(i)?.jsonPrimitive?.contentOrNull

                var layers = MetarRawSkyParser.layersFrom(rawMetar)
                if (layers.isEmpty()) {
                    layers = cloudLayerArrays.mapNotNull { layerArray ->
                        val layerObj = layerArray.getOrNull(i) as? JsonObject
                            ?: return@mapNotNull null
                        val skyCond = layerObj["sky_condition"]?.jsonPrimitive?.contentOrNull
                        val mappedAmount = mapSkyConditionToAmount(skyCond)
                            ?: return@mapNotNull null
                        val heightM = layerObj["height_agl"]?.jsonPrimitive?.doubleOrNull
                        NwsApi.CloudLayer(amount = mappedAmount, baseMeters = heightM)
                    }
                }
                if (layers.isEmpty()) {
                    val mappedAmount = mapSkyConditionToAmount(summary)
                    if (mappedAmount != null) {
                        layers = listOf(NwsApi.CloudLayer(amount = mappedAmount, baseMeters = null))
                    }
                }

                out.add(
                    NwsApi.Observation(
                        timestamp = dateTimeStr,
                        temperatureCelsius = tempC,
                        textDescription = summary,
                        stationName = stationName,
                        maxTempLast24hCelsius = null,
                        minTempLast24hCelsius = null,
                        precipLastHourMm = null,
                        qcFailed = !qcChecks.isNullOrEmpty(),
                        cloudLayers = layers,
                        // A row backed by a raw report IS a METAR — the same thing `rawMessage`
                        // signals on the NWS path. Mesonet stations return no metar_set_1 and stay
                        // false, which is what MetarCloudBlender's METAR-over-ASOS preference needs
                        // to be told honestly.
                        isMetar = !rawMetar.isNullOrBlank(),
                        rawMessage = rawMetar,
                    ),
                )
            }
            return out
        }

        internal fun mapSkyConditionToAmount(cond: String?): String? = when (cond?.trim()?.lowercase()) {
            "clear", "fair", "sunny", "mostly clear", "none", "skc", "clr" -> "CLR"
            "few", "few clouds" -> "FEW"
            "scattered", "scattered clouds", "partly cloudy", "thin scattered", "sct" -> "SCT"
            "broken", "broken clouds", "mostly cloudy", "thin broken", "bkn" -> "BKN"
            "overcast", "cloudy", "thin overcast", "ovc" -> "OVC"
            "obscured", "vertical visibility", "thin obscured", "fog", "vv" -> "VV"
            else -> null
        }

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
                    Log.w(TAG, "Synoptic request failed for $stationId: ${ApiKeyRedaction.redact(message ?: "")}")
                    return FetchOutcome.Failed.of("synoptic: $message")
                }

                // A successful response without station/observation structures is Synoptic's way of
                // saying the station has nothing in the window — definitive NoData, not a failure.
                val firstStation = root["STATION"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return FetchOutcome.NoData
                val observationList = parseStationObservations(firstStation, stationNameFallback)
                if (observationList.isEmpty()) FetchOutcome.NoData else FetchOutcome.Success(observationList)
            } catch (e: Exception) {
                Log.w(TAG, "Synoptic parse for $stationId failed: ${ApiKeyRedaction.redact("$e")}")
                FetchOutcome.Failed.of("parse: ${e.message}")
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
        val initial = executeFetchSynopticObservations(stationId, recentMinutes, stationNameFallback, forceRefresh = false)
        if (initial is FetchOutcome.Failed && canRefetchToken()) {
            Log.i(TAG, "Synoptic observation fetch failed for $stationId (${initial.reason}); refetching token and retrying...")
            return executeFetchSynopticObservations(stationId, recentMinutes, stationNameFallback, forceRefresh = true)
        }
        return initial
    }

    private suspend fun executeFetchSynopticObservations(
        stationId: String,
        recentMinutes: Long,
        stationNameFallback: String,
        forceRefresh: Boolean,
    ): FetchOutcome<List<NwsApi.Observation>> {
        val token = effectiveToken(forceRefresh)
            ?: return FetchOutcome.Failed("synoptic: no token configured")
        val response: String = try {
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
            Log.w(TAG, "Synoptic fallback for $stationId fetch failed: ${ApiKeyRedaction.redact("$e")}")
            return FetchOutcome.failed(e)
        }
        return parseSynopticTimeseries(json, response, stationId, stationNameFallback)
    }

    private fun canRefetchToken(): Boolean =
        tokenProvider()?.takeIf { it.isNotBlank() } == null && mesoTokenApi != null

}
