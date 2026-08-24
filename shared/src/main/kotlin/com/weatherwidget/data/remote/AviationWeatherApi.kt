package com.weatherwidget.data.remote

import com.weatherwidget.shared.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.*
import javax.inject.Inject

/**
 * `aviationweather.gov/api/data` — raw METAR observations, worldwide, no API key.
 *
 * Why this exists alongside [NwsApi]:
 *
 * - **International coverage.** NWS station discovery goes through `/points`, which fails outside
 *   the United States, so non-US users currently have no station observations from any source
 *   (every other provider is model output with `supportsTemperatureActuals = false`). A `bbox`
 *   query works anywhere.
 * - **One call for many stations.** `?ids=A,B,C,D,E` returns all five stations, with history, in a
 *   single request; the NWS path issues one request per station per cycle.
 *
 * Both entry points keep a pure, HTTP-free parse half so fixture JSON drives the tests, matching
 * [SynopticApi.parseSynopticTimeseries].
 */
class AviationWeatherApi @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    suspend fun fetchStations(bbox: String): FetchOutcome<List<AviationWeatherStationFilter.Candidate>> =
        try {
            val body: String = httpClient.get("$BASE_URL/stationinfo") {
                parameter("bbox", bbox)
                parameter("format", "json")
            }.body()
            parseStationInfo(json, body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "stationinfo failed for bbox=$bbox", e)
            FetchOutcome.failed(e)
        }

    suspend fun fetchMetars(stationIds: List<String>, hours: Int): FetchOutcome<List<MetarRow>> {
        if (stationIds.isEmpty()) return FetchOutcome.NoData
        return try {
            val body: String = httpClient.get("$BASE_URL/metar") {
                parameter("ids", stationIds.joinToString(","))
                parameter("format", "json")
                parameter("hours", hours.toString())
            }.body()
            parseMetars(json, body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "metar failed for ids=${stationIds.joinToString(",")}", e)
            FetchOutcome.failed(e)
        }
    }

    /**
     * One decoded METAR. Field types mirror what the API actually emits, not what it documents —
     * see [parseMetars] for the three places those disagree.
     */
    data class MetarRow(
        val stationId: String,
        val stationName: String,
        val observedAtMillis: Long,
        val latitude: Double,
        val longitude: Double,
        val elevationMeters: Double?,
        val temperatureCelsius: Float?,
        val dewpointCelsius: Float?,
        val seaLevelPressureHpa: Float?,
        val cloudLayers: List<NwsApi.CloudLayer>,
        val rawOb: String?,
        val isSpeci: Boolean,
    )

    companion object {
        private const val TAG = "AviationWeatherApi"
        const val BASE_URL = "https://aviationweather.gov/api/data"

        /** Cloud base arrives in hundreds of feet? No — `stationinfo` gives feet outright. */
        private const val METERS_PER_FOOT = 0.3048

        internal fun parseStationInfo(
            json: Json,
            body: String,
        ): FetchOutcome<List<AviationWeatherStationFilter.Candidate>> = try {
            val rows = json.parseToJsonElement(body).jsonArray
            val candidates = rows.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                AviationWeatherStationFilter.Candidate(
                    id = id,
                    name = o["site"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    lat = lat,
                    lon = lon,
                    elevationMeters = o["elev"]?.jsonPrimitive?.doubleOrNull,
                    // Absent or null siteType means "no products listed" — treated as
                    // not-a-METAR-station, never as an unknown worth requesting.
                    siteTypes = (o["siteType"] as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        .orEmpty(),
                    country = o["country"]?.jsonPrimitive?.contentOrNull,
                )
            }
            if (candidates.isEmpty()) FetchOutcome.NoData else FetchOutcome.Success(candidates)
        } catch (e: Exception) {
            FetchOutcome.failed(e)
        }

        /**
         * Pure METAR-array parse.
         *
         * Three field-type hazards, all observed live on 2026-08-23 and all fatal to a naive
         * `jsonPrimitive.int` / `.double`:
         *
         * - `wdir` is `340` on one KNUQ report and `"VRB"` on the very next one — same field, same
         *   station, adjacent cycles.
         * - `visib` is the string `"10+"`, not a number.
         * - `dewp` alternates `15` and `14.4` depending on whether the station emits a T-group.
         *
         * `doubleOrNull` on a `JsonPrimitive` returns null for non-numeric content rather than
         * throwing, so every numeric read here goes through it and a bad value degrades that one
         * field instead of dropping the observation.
         */
        internal fun parseMetars(json: Json, body: String): FetchOutcome<List<MetarRow>> = try {
            val rows = json.parseToJsonElement(body).jsonArray
            val parsed = rows.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val id = o["icaoId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                // obsTime, NOT reportTime. reportTime is pre-rounded to the hour, and the
                // observations primary key is (stationId, timestamp, lat, lon) — two SPECIs inside
                // one hour would collide on it and one would be silently lost.
                val obsTimeSeconds = o["obsTime"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null

                val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null

                MetarRow(
                    stationId = id,
                    stationName = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { id },
                    observedAtMillis = obsTimeSeconds * 1000L,
                    latitude = lat,
                    longitude = lon,
                    elevationMeters = o["elev"]?.jsonPrimitive?.doubleOrNull,
                    temperatureCelsius = o["temp"]?.jsonPrimitive?.doubleOrNull?.toFloat(),
                    dewpointCelsius = o["dewp"]?.jsonPrimitive?.doubleOrNull?.toFloat(),
                    seaLevelPressureHpa = o["slp"]?.jsonPrimitive?.doubleOrNull?.toFloat(),
                    cloudLayers = parseClouds(o["clouds"]),
                    rawOb = o["rawOb"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    isSpeci = o["metarType"]?.jsonPrimitive?.contentOrNull
                        .equals("SPECI", ignoreCase = true),
                )
            }
            if (parsed.isEmpty()) FetchOutcome.NoData else FetchOutcome.Success(parsed)
        } catch (e: Exception) {
            FetchOutcome.failed(e)
        }

        /**
         * `clouds: [{"cover":"SCT","base":8000}]` → [NwsApi.CloudLayer], base feet → metres.
         *
         * An EMPTY array stays empty, and callers must read that as "not reported", never "clear" —
         * the invariant `NwsObservationMapperCloudTest` pins for the NWS path. A `CLR`/`SKC` cover
         * is a positive report of clear sky and keeps its layer, with a null base because a clear
         * report carries no cloud height.
         */
        internal fun parseClouds(element: JsonElement?): List<NwsApi.CloudLayer> {
            val array = element as? JsonArray ?: return emptyList()
            return array.mapNotNull { entry ->
                val o = entry as? JsonObject ?: return@mapNotNull null
                val cover = o["cover"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val baseFeet = o["base"]?.jsonPrimitive?.doubleOrNull
                NwsApi.CloudLayer(
                    amount = cover.uppercase(),
                    baseMeters = baseFeet?.let { it * METERS_PER_FOOT },
                )
            }
        }
    }
}
