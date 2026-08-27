package com.weatherwidget.data.remote

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
 * It used to be a hardcoded literal here — a token scraped from
 * weather.gov's own public station viewer (value recorded in
 * `plans/260628-nws-stale-station-synoptic-fallback.md`). That was never this project's credential:
 * it rode NOAA's quota, and NOAA could rotate it at any time and silently kill the fallback. Blank
 * is now a first-class state — [isConfigured] is false and callers skip the fallback rather than
 * issuing a request that is guaranteed to be rejected.
 */
class SynopticApi(
    private val httpClient: HttpClient,
    private val json: Json,
    private val tokenProvider: () -> String?,
) {
    /** False when no token was baked in or entered; callers must skip Synoptic entirely. */
    val isConfigured: Boolean get() = !tokenProvider().isNullOrBlank()

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
            val stationName = station["NAME"]?.jsonPrimitive?.contentOrNull ?: stationNameFallback

            val out = mutableListOf<NwsApi.Observation>()
            for (i in 0 until dateTimeArray.size) {
                val dateTimeStr = parseTimestampToIsoString(dateTimeArray[i].jsonPrimitive.content)
                // Skip rather than default: a station reporting only humidity is not a temperature
                // observation, and storing 0 C would poison the blend far worse than a missing row.
                val tempC = airTempArray?.getOrNull(i)?.jsonPrimitive?.doubleOrNull?.toFloat() ?: continue

                val qcChecks = airTempQcArray?.getOrNull(i) as? JsonArray
                val summary = weatherSummaryArray?.getOrNull(i)?.jsonPrimitive?.contentOrNull
                    ?: weatherCondArray?.getOrNull(i)?.jsonPrimitive?.contentOrNull
                    ?: "Unknown"
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

        /** One station of a radius query: its identity plus the readings parsed from it. */
        data class RadiusStation(
            val info: NwsApi.StationInfo,
            val distanceKm: Double,
            val elevationMeters: Double?,
            val observations: List<NwsApi.Observation>,
        )

        private const val MILES_TO_KM = 1.609344
        private const val FEET_TO_METERS = 0.3048

        /**
         * Pure parse of a multi-station `stations/timeseries?radius=` response.
         *
         * `DISTANCE` comes back in miles and `ELEVATION` in feet, which is why both are converted
         * here rather than at the call site — the rest of the app is metric internally and a raw
         * mile value silently mis-ranks every station in the IDW blend.
         */
        internal fun parseRadiusTimeseries(
            json: Json,
            response: String,
        ): FetchOutcome<List<RadiusStation>> = try {
            val root = json.parseToJsonElement(response).jsonObject
            val summaryObj = root["SUMMARY"]?.jsonObject
            val responseCode = summaryObj?.get("RESPONSE_CODE")?.jsonPrimitive?.intOrNull
            if (responseCode != 1) {
                FetchOutcome.Failed("synoptic: ${summaryObj?.get("RESPONSE_MESSAGE")?.jsonPrimitive?.contentOrNull}")
            } else {
                val stations = (root["STATION"]?.jsonArray).orEmpty().mapNotNull { element ->
                    val o = element as? JsonObject ?: return@mapNotNull null
                    val stid = o["STID"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val lat = o["LATITUDE"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                        ?: return@mapNotNull null
                    val lon = o["LONGITUDE"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                        ?: return@mapNotNull null
                    val observations = parseStationObservations(o, stid)
                    if (observations.isEmpty()) return@mapNotNull null
                    RadiusStation(
                        info = NwsApi.StationInfo(
                            id = stid,
                            name = o["NAME"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: stid,
                            lat = lat,
                            lon = lon,
                            // Mesonet 1 and 2 are the NWS/FAA networks; everything else in this feed
                            // is a cooperative or personal station, which the blend discounts.
                            type = when (o["MNET_ID"]?.jsonPrimitive?.contentOrNull) {
                                "1", "2" -> NwsApi.StationType.OFFICIAL
                                else -> NwsApi.StationType.PERSONAL
                            },
                        ),
                        distanceKm = (o["DISTANCE"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0) * MILES_TO_KM,
                        elevationMeters = o["ELEVATION"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                            ?.times(FEET_TO_METERS),
                        observations = observations,
                    )
                }
                if (stations.isEmpty()) FetchOutcome.NoData else FetchOutcome.Success(stations)
            }
        } catch (e: Exception) {
            FetchOutcome.failed(e)
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
                    Log.w(TAG, "Synoptic request failed for $stationId: $message")
                    return FetchOutcome.Failed("synoptic: $message")
                }

                // A successful response without station/observation structures is Synoptic's way of
                // saying the station has nothing in the window — definitive NoData, not a failure.
                val firstStation = root["STATION"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return FetchOutcome.NoData
                val observationList = parseStationObservations(firstStation, stationNameFallback)
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
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
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
            Log.w(TAG, "Synoptic fallback for $stationId fetch failed: $e")
            return FetchOutcome.failed(e)
        }
        return parseSynopticTimeseries(json, response, stationId, stationNameFallback)
    }

    /**
     * Radius query for observations across multiple nearby stations.
     */
    suspend fun fetchRadiusTimeseries(
        latitude: Double,
        longitude: Double,
        radiusMiles: Double = 25.0,
        recentMinutes: Long = 120,
    ): FetchOutcome<List<RadiusStation>> {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: return FetchOutcome.Failed("synoptic: no token configured")
        val response: String = try {
            val url = "https://api.synopticdata.com/v2/stations/timeseries"

            httpClient.get(url) {
                parameter("radius", "$latitude,$longitude,$radiusMiles")
                parameter("recent", recentMinutes)
                parameter("token", token)
                parameter("obtimezone", "utc")
                parameter("qc", "on")
                parameter("qc_checks", "all")
                parameter("qc_flags", "on")
                header("Referer", "https://www.weather.gov/wrh/timeseries")
                header("Origin", "https://www.weather.gov")
            }.body()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Synoptic radius fetch for ($latitude, $longitude) failed: $e")
            return FetchOutcome.failed(e)
        }
        return parseRadiusTimeseries(json, response)
    }

}
