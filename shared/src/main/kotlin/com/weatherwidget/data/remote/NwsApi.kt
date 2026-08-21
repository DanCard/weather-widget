package com.weatherwidget.data.remote

import com.weatherwidget.shared.observations.NwsQualityControl
import com.weatherwidget.shared.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.roundToInt

class NwsPointUnavailableException(
    detail: String,
) : ApiAccessException(
        source = com.weatherwidget.data.model.WeatherSource.NWS,
        statusCode = HttpStatusCode.NotFound.value,
        detail = detail,
        message = "NWS does not provide data for the requested point.",
    )

class NwsApi
    @Inject
    constructor(
        private val httpClient: HttpClient,
        private val json: Json,
    ) {
        companion object {
            private const val TAG = "NwsApi"
            private const val BASE_URL = "https://api.weather.gov"
            private const val USER_AGENT = "WeatherWidget/1.0 (contact@weatherwidget.app)"

            fun classifyStationType(id: String): StationType {
                return if (id.length == 4 && (id.startsWith("K") || id.startsWith("P") || id.startsWith("T"))) {
                    StationType.OFFICIAL
                } else {
                    StationType.PERSONAL
                }
            }

            fun encodeStationInfo(station: StationInfo): String {
                return listOf(
                    station.id,
                    station.name,
                    station.lat.toString(),
                    station.lon.toString(),
                    station.type.name,
                ).joinToString("\t")
            }

            fun decodeStationInfo(serialized: String): StationInfo? {
                if (serialized.isBlank()) return null

                val tabParts = serialized.split("\t")
                if (tabParts.size >= 4) {
                    val id = tabParts[0]
                    val name = tabParts[1]
                    val lat = tabParts[2].toDoubleOrNull() ?: return null
                    val lon = tabParts[3].toDoubleOrNull() ?: return null
                    val type = tabParts.getOrNull(4)?.let { raw ->
                        StationType.entries.firstOrNull { it.name == raw }
                    } ?: classifyStationType(id)
                    return StationInfo(id = id, name = name, lat = lat, lon = lon, type = type)
                }

                val commaParts = serialized.split(",")
                if (commaParts.size < 4) return null
                val id = commaParts.first()
                val lat = commaParts[commaParts.size - 2].toDoubleOrNull() ?: return null
                val lon = commaParts.last().toDoubleOrNull() ?: return null
                val name = commaParts.subList(1, commaParts.size - 2).joinToString(",").trim()
                return StationInfo(
                    id = id,
                    name = name.ifEmpty { id },
                    lat = lat,
                    lon = lon,
                    type = classifyStationType(id),
                )
            }

            internal fun parseObservationProperties(props: JsonObject, defaultStationName: String): Observation? {
                val timestamp = props["timestamp"]?.jsonPrimitive?.content ?: return null
                val stationName = props["stationName"]?.jsonPrimitive?.content ?: defaultStationName

                // Temperature is in a value object with unitCode
                val tempObj = props["temperature"]?.jsonObject
                val tempValue = tempObj?.get("value")?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null

                val textDescription = props["textDescription"]?.jsonPrimitive?.content ?: "Unknown"

                val maxTempObj = props["maxTemperatureLast24Hours"]?.jsonObject
                val maxTempValue = maxTempObj?.get("value")?.jsonPrimitive?.content?.toFloatOrNull()
                val minTempObj = props["minTemperatureLast24Hours"]?.jsonObject
                val minTempValue = minTempObj?.get("value")?.jsonPrimitive?.content?.toFloatOrNull()

                // Measured precip from NWS observations (mm). Only the last-hour amount is used;
                // it's summed across a day's observations for measured daily/day-night totals.
                val precipLastHourObj = props["precipitationLastHour"]?.jsonObject
                val precipLastHourMm = precipLastHourObj?.get("value")?.jsonPrimitive?.content?.toFloatOrNull()

                // METAR sky condition, the same layer list every official station reports. An empty
                // list means "not reported", never "clear": personal stations return [] on every
                // report and partial official reports omit sky condition entirely. See MetarSkyCover.
                // Parsing is non-fatal by construction (safe casts only): a malformed or JSON-null
                // cloudLayers degrades to "not reported" and must never drop the observation's
                // temperature with it.
                val cloudLayers = parseCloudLayers(props["cloudLayers"])

                // NWS's own verdict on the value it just handed us. Marked (not dropped) so the
                // stations list can show the failure, exactly as Synoptic-flagged readings are.
                val qualityControl = tempObj.get("qualityControl")?.jsonPrimitive?.contentOrNull

                return Observation(
                    timestamp = timestamp,
                    temperatureCelsius = tempValue.toFloat(),
                    textDescription = textDescription,
                    stationName = stationName,
                    maxTempLast24hCelsius = maxTempValue,
                    minTempLast24hCelsius = minTempValue,
                    precipLastHourMm = precipLastHourMm,
                    qcFailed = NwsQualityControl.isFailed(qualityControl),
                    cloudLayers = cloudLayers,
                )
            }

            private val warnedBaseUnits = java.util.Collections.synchronizedSet(mutableSetOf<String>())

            /**
             * Parses the METAR `cloudLayers` array into [CloudLayer]s with heights in metres. An absent
             * or JSON-null array yields the empty list ("not reported"), which
             * [com.weatherwidget.shared.observations.MetarSkyCover] maps to null rather than 0. `base`
             * is only ever used to decide layer membership for the low-layer read; percent keys on
             * `amount` alone. `wmoUnit:m` is all that has been observed; `ft` is handled defensively
             * and anything else is logged once.
             *
             * Uses safe casts exclusively — no `jsonObject`/`jsonPrimitive` unchecked conversions — so
             * any unexpected shape (a JSON null, a non-object layer, a missing amount) degrades to a
             * skipped layer instead of throwing out of [parseObservationProperties] and dropping the
             * whole observation, temperature included.
             */
            private fun parseCloudLayers(node: JsonElement?): List<CloudLayer> {
                val array = node as? JsonArray ?: return emptyList()
                return array.mapNotNull { layer ->
                    val obj = layer as? JsonObject ?: return@mapNotNull null
                    val amount = (obj["amount"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                        ?: return@mapNotNull null
                    val base = obj["base"] as? JsonObject
                    val rawValue = (base?.get("value") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                    val unitCode = (base?.get("unitCode") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                    if (amount == null) return@mapNotNull null
                    val baseMeters = when (unitCode) {
                        null, "", "wmoUnit:m" -> rawValue?.toDoubleOrNull()
                        "wmoUnit:ft" -> rawValue?.toDoubleOrNull()?.times(0.3048)
                        else -> {
                            if (warnedBaseUnits.add(unitCode)) {
                                Log.w(TAG, "parseCloudLayers: unexpected base unitCode=$unitCode raw=$rawValue; height treated as unknown")
                            }
                            null
                        }
                    }
                    CloudLayer(amount = amount, baseMeters = baseMeters)
                }
            }

            /**
             * Pure half of the latest-valid-observation lookup: walks the station's recent
             * reports (newest first) for one that parses to a usable observation (a report with
             * a null temperature — e.g. KNUQ during its 2026-07-13 feed corruption — is not
             * usable). Kept HTTP-free so fixture JSON can drive the outcome matrix in tests.
             * NoData = well-formed response with no usable report; Failed = malformed response.
             */
            internal fun selectValidObservation(json: Json, responseJson: String, stationId: String): FetchOutcome<Observation> {
                return try {
                    val jsonObj = json.parseToJsonElement(responseJson).jsonObject
                    val features = jsonObj["features"]?.jsonArray
                        ?: return FetchOutcome.Failed("missing features array")
                    for (feature in features) {
                        val props = feature.jsonObject["properties"]?.jsonObject ?: continue
                        val obs = parseObservationProperties(props, stationId)
                        if (obs != null) {
                            return FetchOutcome.Success(obs)
                        }
                    }
                    FetchOutcome.NoData
                } catch (e: Exception) {
                    FetchOutcome.Failed("parse: ${e.message}")
                }
            }
        }

        suspend fun getGridPoint(
            lat: Double,
            lon: Double,
        ): GridPointInfo {
            val response =
                httpClient.get("$BASE_URL/points/$lat,$lon") {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/json")
                }
            val responseBody = response.bodyAsText()

            if (response.status.value !in 200..299) {
                if (response.status == HttpStatusCode.NotFound && isUnsupportedPointProblem(responseBody)) {
                    throw NwsPointUnavailableException(responseBody)
                }
                throw ApiAccessException(
                    source = com.weatherwidget.data.model.WeatherSource.NWS,
                    statusCode = response.status.value,
                    detail = responseBody,
                    message = "NWS points lookup failed: status ${response.status.value}.",
                )
            }

            val jsonObj = json.parseToJsonElement(responseBody).jsonObject
            val properties =
                jsonObj["properties"]?.jsonObject
                    ?: throw Exception("Invalid NWS response")

            // Extract observation stations URL
            val observationStationsUrl = properties["observationStations"]?.jsonPrimitive?.content

            return GridPointInfo(
                gridId = properties["gridId"]?.jsonPrimitive?.content ?: "",
                gridX = properties["gridX"]?.jsonPrimitive?.content?.toInt() ?: 0,
                gridY = properties["gridY"]?.jsonPrimitive?.content?.toInt() ?: 0,
                forecastUrl = properties["forecast"]?.jsonPrimitive?.content ?: "",
                observationStationsUrl = observationStationsUrl,
            )
        }

        private fun isUnsupportedPointProblem(responseBody: String): Boolean {
            val problem = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull()
                ?: return false
            val type = problem["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val title = problem["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return type.substringAfterLast('/').equals("InvalidPoint", ignoreCase = true) ||
                title.equals("Data Unavailable For Requested Point", ignoreCase = true)
        }

        suspend fun getObservationStations(stationsUrl: String): List<StationInfo> {
            val response: String =
                httpClient.get(stationsUrl) {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/geo+json")
                }.body()

            val jsonObj = json.parseToJsonElement(response).jsonObject
            val features = jsonObj["features"]?.jsonArray ?: return emptyList()

            return features.mapNotNull { feature ->
                val featObj = feature.jsonObject
                val props = featObj["properties"]?.jsonObject ?: return@mapNotNull null
                val id = props["stationIdentifier"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = props["name"]?.jsonPrimitive?.content ?: id
                
                // Detection logic: Official METAR stations are 4-chars starting with K, P, or T.
                val type = classifyStationType(id)

                val geometry = featObj["geometry"]?.jsonObject
                val coords = geometry?.get("coordinates")?.jsonArray
                if (coords != null && coords.size >= 2) {
                    StationInfo(
                        id = id,
                        name = name,
                        lon = coords[0].jsonPrimitive.content.toDouble(),
                        lat = coords[1].jsonPrimitive.content.toDouble(),
                        type = type
                    )
                } else {
                    null
                }
            }
        }

        enum class StationType {
            OFFICIAL,
            PERSONAL,
            UNKNOWN
        }

        data class StationInfo(
            val id: String,
            val name: String,
            val lat: Double,
            val lon: Double,
            val type: StationType = StationType.UNKNOWN
        )

        suspend fun getLatestObservation(stationId: String): Observation? {
            val response: String =
                httpClient.get("$BASE_URL/stations/$stationId/observations/latest") {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/geo+json")
                }.body()

            val jsonObj = json.parseToJsonElement(response).jsonObject
            val props = jsonObj["properties"]?.jsonObject ?: return null
            val timestamp = props["timestamp"]?.jsonPrimitive?.content ?: return null

            // Temperature is in a value object with unitCode
            val tempObj = props["temperature"]?.jsonObject
            val tempValue = tempObj?.get("value")?.jsonPrimitive?.content?.toDoubleOrNull()

            val textDescription = props["textDescription"]?.jsonPrimitive?.content ?: "Unknown"

            // `/observations/latest` carries the SAME `cloudLayers` array as the `?start=&end=`
            // series, and omitting it here left every station reached by this path storing no sky
            // condition at all. Measured 2026-08-21: KNUQ (3.8 km, the nearest official station)
            // published `OVC 400` on every report while the DB held 23 consecutive cloud-less rows,
            // so the cloud blend fell back to KSJC 15.9 km away.
            //
            // NOTE: this function is a partial hand-rolled copy of parseObservationProperties above,
            // which is what let the two drift. Unifying them would also start populating precip and
            // 24h min/max on this path, so it is deliberately left alone here.
            val cloudLayers = parseCloudLayers(props["cloudLayers"])

            return if (tempValue != null) {
                Observation(
                    timestamp = timestamp,
                    temperatureCelsius = tempValue.toFloat(),
                    textDescription = textDescription,
                    qcFailed = NwsQualityControl.isFailed(
                        tempObj["qualityControl"]?.jsonPrimitive?.contentOrNull,
                    ),
                    cloudLayers = cloudLayers,
                )
            } else {
                Log.d("NwsApi", "getLatestObservation: station=$stationId has null temperature value")
                null
            }
        }

        suspend fun getObservations(
            stationId: String,
            start: String,
            end: String,
        ): List<Observation> {
            val response: String =
                httpClient.get("$BASE_URL/stations/$stationId/observations") {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/json")
                    parameter("start", start)
                    parameter("end", end)
                }.body()

            val jsonObj = json.parseToJsonElement(response).jsonObject
            val features = jsonObj["features"]?.jsonArray ?: return emptyList()

            // Delegate per-feature parsing to the shared parser so historical observations
            // carry precipitationLastHour through to ObservationEntity — the same measured
            // precip field getLatestObservationDetailed already extracts.
            return features.mapNotNull { feature ->
                val props = feature.jsonObject["properties"]?.jsonObject ?: return@mapNotNull null
                parseObservationProperties(props, defaultStationName = stationId)
            }
        }

        suspend fun getForecast(gridPoint: GridPointInfo): List<ForecastPeriod> {
            val fetchStartedAt = System.currentTimeMillis()
            val response: String =
                httpClient.get(gridPoint.forecastUrl) {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/json")
                }.body()

            val jsonObj = json.parseToJsonElement(response).jsonObject
            val properties = jsonObj["properties"]?.jsonObject
            val updated = properties?.get("updated")?.jsonPrimitive?.content
            val generatedAt = properties?.get("generatedAt")?.jsonPrimitive?.content
            val periods =
                properties?.get("periods")?.jsonArray
                    ?: return emptyList()

            Log.i(
                TAG,
                "getForecast: url=${gridPoint.forecastUrl}, fetchedAt=$fetchStartedAt, updated=$updated, generatedAt=$generatedAt, periodCount=${periods.size}",
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
                val precipProbability =
                    obj["probabilityOfPrecipitation"]?.jsonObject
                        ?.get("value")?.jsonPrimitive?.content?.toIntOrNull()
                val precipAmountMm = parseQuantitativePrecipitationMm(obj["quantitativePrecipitation"]?.jsonObject)

                Log.d(
                    TAG,
                    "getForecast[$index]: name=$name start=$startTime end=$endTime tempRaw=$tempRaw tempRounded=$temperature unit=$temperatureUnit isDaytime=$isDaytime short=$shortForecast pop=$precipProbability qpfMm=$precipAmountMm",
                )

                ForecastPeriod(
                    name = name,
                    startTime = startTime,
                    endTime = endTime,
                    temperature = temperature,
                    temperatureUnit = temperatureUnit,
                    shortForecast = shortForecast,
                    isDaytime = isDaytime,
                    precipProbability = precipProbability,
                    precipAmountMm = precipAmountMm,
                )
            }
        }

        suspend fun getHourlyForecast(gridPoint: GridPointInfo): List<HourlyForecastPeriod> {
            val url = "$BASE_URL/gridpoints/${gridPoint.gridId}/${gridPoint.gridX},${gridPoint.gridY}/forecast/hourly"
            val response: String =
                httpClient.get(url) {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/json")
                }.body()

            val jsonObj = json.parseToJsonElement(response).jsonObject
            val periods =
                jsonObj["properties"]?.jsonObject?.get("periods")?.jsonArray
                    ?: return emptyList()

            return periods.mapNotNull { period ->
                val obj = period.jsonObject
                val startTimeStr = obj["startTime"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val startDateTime = try {
                    java.time.ZonedDateTime.parse(startTimeStr)
                } catch (e: Exception) {
                    return@mapNotNull null
                }
                val startTime = startDateTime.toInstant().toEpochMilli()
                val temperature = obj["temperature"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val temperatureUnit = obj["temperatureUnit"]?.jsonPrimitive?.content ?: "F"
                val shortForecast = obj["shortForecast"]?.jsonPrimitive?.content ?: "Unknown"
                val precipProbability =
                    obj["probabilityOfPrecipitation"]?.jsonObject
                        ?.get("value")?.jsonPrimitive?.content?.toIntOrNull()
                val precipAmountMm = parseQuantitativePrecipitationMm(obj["quantitativePrecipitation"]?.jsonObject)

                // Convert to Fahrenheit if needed (NWS usually returns F)
                val tempF =
                    if (temperatureUnit == "C") {
                        (temperature.toFloat() * 1.8f) + 32f
                    } else {
                        temperature.toFloat()
                    }

                HourlyForecastPeriod(
                    startTime = startTime,
                    localDate = startDateTime.toLocalDate().toString(),
                    localHour = startDateTime.hour,
                    temperature = tempF,
                    shortForecast = shortForecast,
                    precipProbability = precipProbability,
                    precipAmountMm = precipAmountMm,
                )
            }
        }

        /**
         * Fetch sky cover, QPF, and daily temperature extremes from the raw gridpoints
         * endpoint in a single HTTP call.
         */
        suspend fun getGridpointsBundle(gridPoint: GridPointInfo): GridpointsBundle {
            val url = "$BASE_URL/gridpoints/${gridPoint.gridId}/${gridPoint.gridX},${gridPoint.gridY}"
            val response: String =
                httpClient.get(url) {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/json")
                }.body()

            val properties = json.parseToJsonElement(response).jsonObject["properties"]?.jsonObject
                ?: return GridpointsBundle(emptyMap(), emptyList(), DailyTemperatureExtremes(emptyMap(), emptyMap()))

            val skyCoverByHour = parseSkyCoverFromProperties(properties)
            val qpfIntervals = parseQpfFromProperties(properties)
            val rejectedTemps = mutableListOf<RejectedNwsTemperature>()
            val maxByDate = parseDailyExtremes(properties["maxTemperature"]?.jsonObject, isMax = true, rejected = rejectedTemps)
            val minByDate = parseDailyExtremes(properties["minTemperature"]?.jsonObject, isMax = false, rejected = rejectedTemps)

            Log.d(
                TAG,
                "getGridpointsBundle: skyCover=${skyCoverByHour.size}h qpf=${qpfIntervals.size} maxDays=${maxByDate.size} minDays=${minByDate.size} rejected=${rejectedTemps.size}",
            )
            return GridpointsBundle(
                skyCoverByHour,
                qpfIntervals,
                DailyTemperatureExtremes(maxByDate, minByDate, rejectedTemps),
            )
        }

        private fun parseSkyCoverFromProperties(properties: JsonObject): Map<String, Int> {
            val skyCover = properties["skyCover"]?.jsonObject
            val values = skyCover?.get("values")?.jsonArray ?: return emptyMap()

            val result = mutableMapOf<String, Int>()
            for (entry in values) {
                val obj = entry.jsonObject
                val validTime = obj["validTime"]?.jsonPrimitive?.content ?: continue
                val value = obj["value"]?.jsonPrimitive?.content?.toDoubleOrNull()?.roundToInt() ?: continue

                // validTime format: "2026-03-14T14:00:00+00:00/PT1H" or "PT3H"
                val slashIndex = validTime.indexOf('/')
                if (slashIndex == -1) continue
                val startTimeStr = validTime.substring(0, slashIndex)
                val durationStr = validTime.substring(slashIndex + 1)

                val durationHours = Regex("PT(\\d+)H").find(durationStr)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                val startZdt = runCatching { java.time.ZonedDateTime.parse(startTimeStr) }.getOrNull() ?: continue
                for (h in 0 until durationHours) {
                    val hourZdt = startZdt.plusHours(h.toLong()).withZoneSameInstant(java.time.ZoneId.systemDefault())
                    val hourKey = hourZdt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                    result[hourKey] = value
                }
            }
            return result
        }

        private fun parseQpfFromProperties(properties: JsonObject): List<QuantitativePrecipitationInterval> {
            val values =
                properties["quantitativePrecipitation"]?.jsonObject
                    ?.get("values")?.jsonArray
                    ?: return emptyList()

            return values.mapNotNull { entry ->
                val obj = entry.jsonObject
                val validTime = obj["validTime"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val slashIndex = validTime.indexOf('/')
                if (slashIndex == -1) return@mapNotNull null

                val start = runCatching { ZonedDateTime.parse(validTime.substring(0, slashIndex)) }.getOrNull() ?: return@mapNotNull null
                val duration = runCatching { Duration.parse(validTime.substring(slashIndex + 1)) }.getOrNull() ?: return@mapNotNull null
                val end = start.plus(duration)
                val amountMm = parseQuantitativePrecipitationMm(obj) ?: return@mapNotNull null

                QuantitativePrecipitationInterval(
                    startTime = start.toInstant().toEpochMilli(),
                    endTime = end.toInstant().toEpochMilli(),
                    amountMm = amountMm,
                )
            }
        }

        private fun parseDailyExtremes(
            node: JsonObject?,
            isMax: Boolean,
            rejected: MutableList<RejectedNwsTemperature>,
        ): Map<String, Float> {
            val values = node?.get("values")?.jsonArray ?: return emptyMap()
            val unitCode = node["uom"]?.jsonPrimitive?.content
            val zone = java.time.ZoneId.systemDefault()
            val result = mutableMapOf<String, Float>()

            for (entry in values) {
                val obj = entry.jsonObject
                val validTime = obj["validTime"]?.jsonPrimitive?.content ?: continue
                val rawValue = obj["value"]?.jsonPrimitive?.content?.toFloatOrNull() ?: continue

                val slashIndex = validTime.indexOf('/')
                if (slashIndex == -1) continue
                val start = runCatching { ZonedDateTime.parse(validTime.substring(0, slashIndex)) }.getOrNull() ?: continue
                val duration = runCatching { Duration.parse(validTime.substring(slashIndex + 1)) }.getOrNull() ?: continue
                val end = start.plus(duration)

                // For maxTemperature (daytime windows), date = local date of start.
                // For minTemperature (overnight windows that cross midnight), date = local date the night ends.
                val dateString = if (isMax) {
                    start.withZoneSameInstant(zone).toLocalDate().toString()
                } else {
                    end.minusMinutes(1).withZoneSameInstant(zone).toLocalDate().toString()
                }

                val tempF = when (unitCode) {
                    "wmoUnit:degF" -> rawValue
                    null, "", "wmoUnit:degC" -> (rawValue * 1.8f) + 32f
                    else -> (rawValue * 1.8f) + 32f
                }

                // NWS leaks a -100°F missing-data sentinel here as -73.333degC. Drop it rather than
                // let it become the day's low — the hourly series carries the real value.
                if (!NwsTemperaturePlausibility.isPlausibleF(tempF)) {
                    rejected += RejectedNwsTemperature(
                        origin = if (isMax) "GRID:max" else "GRID:min",
                        dateString = dateString,
                        isMax = isMax,
                        windowStartMs = start.toInstant().toEpochMilli(),
                        windowEndMs = end.toInstant().toEpochMilli(),
                        rawValueF = tempF,
                    )
                    Log.w(TAG, "parseDailyExtremes: rejected implausible ${if (isMax) "max" else "min"}=$tempF°F date=$dateString validTime=$validTime raw=$rawValue uom=$unitCode")
                    continue
                }

                val existing = result[dateString]
                result[dateString] = when {
                    existing == null -> tempF
                    isMax -> kotlin.math.max(existing, tempF)
                    else -> kotlin.math.min(existing, tempF)
                }
            }
            return result
        }

        /**
         * Latest usable observation for a station, walking its recent reports (newest first)
         * past unusable ones (null temperature). Returns a tri-state [FetchOutcome] so callers
         * can tell a station that is definitively silent (NoData) from a lookup that never got
         * an answer (Failed) — the two demand opposite handling (record the attempt vs report
         * the error and leave the station's record alone).
         */
        suspend fun getLatestObservationDetailedResult(stationId: String, limit: Int = 10): FetchOutcome<Observation> {
            val response: String = try {
                httpClient.get("$BASE_URL/stations/$stationId/observations?limit=$limit") {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/geo+json")
                }.body()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "getLatestObservationDetailedResult: query failed for $stationId: ${e.message}")
                return FetchOutcome.failed(e)
            }
            val outcome = selectValidObservation(json, response, stationId)
            when (outcome) {
                is FetchOutcome.Success ->
                    Log.d(TAG, "getLatestObservationDetailedResult: $stationId valid data from ${outcome.value.timestamp}")
                is FetchOutcome.NoData ->
                    Log.w(TAG, "getLatestObservationDetailedResult: $stationId no valid data in last $limit observations")
                is FetchOutcome.Failed ->
                    Log.e(TAG, "getLatestObservationDetailedResult: $stationId ${outcome.reason}")
            }
            return outcome
        }

        data class GridPointInfo(
            val gridId: String,
            val gridX: Int,
            val gridY: Int,
            val forecastUrl: String,
            val observationStationsUrl: String? = null,
        )

        data class ForecastPeriod(
            val name: String,
            val startTime: String,
            val endTime: String,
            val temperature: Int,
            val temperatureUnit: String,
            val shortForecast: String,
            val isDaytime: Boolean,
            val precipProbability: Int? = null,
            val precipAmountMm: Float? = null,
        )

        data class Observation(
            val timestamp: String,
            val temperatureCelsius: Float,
            val textDescription: String,
            val stationName: String = "",
            val maxTempLast24hCelsius: Float? = null,
            val minTempLast24hCelsius: Float? = null,
            val precipLastHourMm: Float? = null,
            // Upstream quality control rejected this reading (e.g. Synoptic check 105, spatial
            // value vs neighbors). Kept for the stations UI; must never enter temperature math.
            val qcFailed: Boolean = false,
            // METAR sky condition. Empty = "not reported"; personal stations always return empty.
            val cloudLayers: List<CloudLayer> = emptyList(),
        )

        /** One METAR sky-condition layer. Percent is derived from [amount] alone (see MetarSkyCover). */
        data class CloudLayer(
            val amount: String,
            val baseMeters: Double?,
        )

        data class HourlyForecastPeriod(
            val startTime: Long, // Epoch ms
            val localDate: String,
            val localHour: Int,
            val temperature: Float, // Fahrenheit
            val shortForecast: String,
            val precipProbability: Int? = null,
            val precipAmountMm: Float? = null,
            val cloudCover: Int? = null, // Sky cover percentage (0-100)
        )

        data class QuantitativePrecipitationInterval(
            val startTime: Long,
            val endTime: Long,
            val amountMm: Float,
        )

        data class DailyTemperatureExtremes(
            val maxByDate: Map<String, Float>,
            val minByDate: Map<String, Float>,
            /** Values the plausibility gate refused, for the hourly repair path and diagnostics. */
            val rejected: List<RejectedNwsTemperature> = emptyList(),
        )

        data class GridpointsBundle(
            val skyCoverByHour: Map<String, Int>,
            val qpfIntervals: List<QuantitativePrecipitationInterval>,
            val dailyTemperatures: DailyTemperatureExtremes,
        )

        private fun parseQuantitativePrecipitationMm(obj: JsonObject?): Float? {
            if (obj == null) return null
            val value = obj["value"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return null
            val unitCode = obj["unitCode"]?.jsonPrimitive?.content
            return when (unitCode) {
                null, "", "wmoUnit:mm" -> value
                "wmoUnit:cm" -> value * 10f
                "wmoUnit:m" -> value * 1000f
                "wmoUnit:in" -> value * 25.4f
                else -> value
            }
        }
    }
