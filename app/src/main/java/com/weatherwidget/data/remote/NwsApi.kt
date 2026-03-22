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
        }

        suspend fun getGridPoint(
            lat: Double,
            lon: Double,
        ): GridPointInfo {
            val response: String =
                httpClient.get("$BASE_URL/points/$lat,$lon") {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/json")
                }.body()

            val jsonObj = json.parseToJsonElement(response).jsonObject
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

            return if (tempValue != null) {
                Observation(
                    timestamp = timestamp,
                    temperatureCelsius = tempValue.toFloat(),
                    textDescription = textDescription,
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

            return features.mapNotNull { feature ->
                val props = feature.jsonObject["properties"]?.jsonObject ?: return@mapNotNull null
                val timestamp = props["timestamp"]?.jsonPrimitive?.content ?: return@mapNotNull null

                // Temperature is in a value object with unitCode
                val tempObj = props["temperature"]?.jsonObject
                val tempValue = tempObj?.get("value")?.jsonPrimitive?.content?.toDoubleOrNull()

                val textDescription = props["textDescription"]?.jsonPrimitive?.content ?: "Unknown"

                val maxTempObj = props["maxTemperatureLast24Hours"]?.jsonObject
                val maxTempValue = maxTempObj?.get("value")?.jsonPrimitive?.content?.toFloatOrNull()
                val minTempObj = props["minTemperatureLast24Hours"]?.jsonObject
                val minTempValue = minTempObj?.get("value")?.jsonPrimitive?.content?.toFloatOrNull()

                if (tempValue != null) {
                    Observation(
                        timestamp = timestamp,
                        temperatureCelsius = tempValue.toFloat(),
                        textDescription = textDescription,
                        maxTempLast24hCelsius = maxTempValue,
                        minTempLast24hCelsius = minTempValue,
                    )
                } else {
                    null
                }
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

                Log.d(
                    TAG,
                    "getForecast[$index]: name=$name start=$startTime end=$endTime tempRaw=$tempRaw tempRounded=$temperature unit=$temperatureUnit isDaytime=$isDaytime short=$shortForecast pop=$precipProbability",
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
                )
            }
        }

        /**
         * Fetch sky cover (cloud cover %) from the raw gridpoints endpoint.
         * Returns a map of ISO 8601 hour key (e.g. "2026-03-14T14:00") to cover percent (0-100).
         */
        suspend fun getSkyCover(gridPoint: GridPointInfo): Map<String, Int> {
            val url = "$BASE_URL/gridpoints/${gridPoint.gridId}/${gridPoint.gridX},${gridPoint.gridY}"
            val response: String =
                httpClient.get(url) {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/json")
                }.body()

            val jsonObj = json.parseToJsonElement(response).jsonObject
            val skyCover = jsonObj["properties"]?.jsonObject?.get("skyCover")?.jsonObject
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

                // Parse duration hours (PT1H, PT2H, PT3H, etc.)
                val durationHours = Regex("PT(\\d+)H").find(durationStr)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                // Parse start time and expand intervals
                val startZdt = runCatching { java.time.ZonedDateTime.parse(startTimeStr) }.getOrNull() ?: continue
                for (h in 0 until durationHours) {
                    val hourZdt = startZdt.plusHours(h.toLong()).withZoneSameInstant(java.time.ZoneId.systemDefault())
                    val hourKey = hourZdt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
                    result[hourKey] = value
                }
            }
            Log.d(TAG, "getSkyCover: parsed ${result.size} hourly entries from gridpoints")
            return result
        }

        private fun parseObservationProperties(props: JsonObject, defaultStationName: String): Observation? {
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

            return Observation(
                timestamp = timestamp,
                temperatureCelsius = tempValue.toFloat(),
                textDescription = textDescription,
                stationName = stationName,
                maxTempLast24hCelsius = maxTempValue,
                minTempLast24hCelsius = minTempValue,
            )
        }

        suspend fun getLatestObservationDetailed(stationId: String): Observation? {
            var primaryObs: Observation? = null
            var primaryFailed = false
            
            try {
                val response: String =
                    httpClient.get("$BASE_URL/stations/$stationId/observations/latest") {
                        header("User-Agent", USER_AGENT)
                        header("Accept", "application/geo+json")
                    }.body()

                val jsonObj = json.parseToJsonElement(response).jsonObject
                val props = jsonObj["properties"]?.jsonObject
                
                if (props != null) {
                    primaryObs = parseObservationProperties(props, stationId)
                    if (primaryObs == null) {
                        Log.d("NwsApi", "getLatestObservationDetailed: station=$stationId latest has null temperature value. Triggering fallback.")
                        primaryFailed = true
                    }
                } else {
                    primaryFailed = true
                }
            } catch (e: Exception) {
                Log.w("NwsApi", "getLatestObservationDetailed: Error fetching latest for $stationId: ${e.message}. Triggering fallback.")
                primaryFailed = true
            }

            if (primaryFailed) {
                return getRecentValidObservationDetailed(stationId)
            }
            return primaryObs
        }

        private suspend fun getRecentValidObservationDetailed(stationId: String, limit: Int = 10): Observation? {
            return try {
                val response: String =
                    httpClient.get("$BASE_URL/stations/$stationId/observations?limit=$limit") {
                        header("User-Agent", USER_AGENT)
                        header("Accept", "application/geo+json")
                    }.body()

                val jsonObj = json.parseToJsonElement(response).jsonObject
                val features = jsonObj["features"]?.jsonArray ?: return null

                for (feature in features) {
                    val props = feature.jsonObject["properties"]?.jsonObject ?: continue
                    val obs = parseObservationProperties(props, stationId)
                    if (obs != null) {
                        Log.d("NwsApi", "getRecentValidObservationDetailed: Fallback for $stationId found valid data from ${obs.timestamp}")
                        return obs
                    }
                }
                
                Log.w("NwsApi", "getRecentValidObservationDetailed: Fallback for $stationId found no valid data in last $limit observations")
                null
            } catch (e: Exception) {
                Log.e("NwsApi", "getRecentValidObservationDetailed: Fallback query failed for $stationId: ${e.message}")
                null
            }
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
        )

        data class Observation(
            val timestamp: String,
            val temperatureCelsius: Float,
            val textDescription: String,
            val stationName: String = "",
            val maxTempLast24hCelsius: Float? = null,
            val minTempLast24hCelsius: Float? = null,
        )

        data class HourlyForecastPeriod(
            val startTime: Long, // Epoch ms
            val localDate: String,
            val localHour: Int,
            val temperature: Float, // Fahrenheit
            val shortForecast: String,
            val precipProbability: Int? = null,
            val cloudCover: Int? = null, // Sky cover percentage (0-100)
        )
    }
