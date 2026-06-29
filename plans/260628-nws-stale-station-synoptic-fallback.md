# Plan: NWS Observation Station Stale/Fallback to Synoptic on Desktop

## Problem Statement
The NWS API (`api.weather.gov`) observations endpoint sometimes lags behind or stops updating for hours for specific stations (e.g., `KNUQ`), while the official NWS Web Interface (Time Series Viewer at `weather.gov/wrh/timeseries`) displays real-time updates. This causes the Desktop Weather companion app to display stale current temperatures or fall back to hourly forecast interpolations instead of showing the actual current temperature.

## Proposed Solution
The NWS Time Series Viewer fetches real-time observations from the Synoptic/MesoWest API (`api.synopticdata.com`) using a public NWS token. We can query the same endpoint when NWS API observations are missing or stale (older than 1 hour) for the top 2 observation stations. 

To achieve this, we will:
1. Identify the public weather.gov token used by the viewer: `7c76618b66c74aee913bdbae4b448bdd`.
2. Query `https://api.synopticdata.com/v2/stations/timeseries` for the station ID.
3. Supply the required headers to bypass token domain restrictions:
   - `Referer: https://www.weather.gov/wrh/timeseries?site=<STATION_ID>`
   - `Origin: https://www.weather.gov`
4. Parse the returned JSON payload to align `date_time`, `air_temp_set_1` (temperature in Celsius), and `weather_summary_set_1d` / `weather_condition_set_1d` (weather description).
5. Build an `ObservationBundle` object consisting of:
   - `latest`: The single most recent observation (the last element in the response).
   - `historical`: All preceding observations (to populate the actuals graph).
6. Integrate this fallback logic into `DesktopWeatherService.fetchObservationBundles` for the top 2 stations.

---

## Technical Details & Code Changes

### 1. Import Additions
In [DesktopWeatherService.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherService.kt):
```kotlin
import io.ktor.client.call.body
import io.ktor.client.request.parameter
import io.ktor.client.request.header
import kotlinx.serialization.json.*
```

### 2. Implement `fetchSynopticObservations`
Add the following helper method in [DesktopWeatherService.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherService.kt):
```kotlin
    private suspend fun fetchSynopticObservations(
        station: NwsApi.StationInfo,
        historyDays: Long
    ): ObservationBundle? = bestEffort("Synoptic fallback for ${station.id}") {
        val minutes = historyDays * 24 * 60
        val token = "7c76618b66c74aee913bdbae4b448bdd"
        val url = "https://api.synopticdata.com/v2/stations/timeseries"

        val response: String = httpClient.get(url) {
            parameter("STID", station.id)
            parameter("recent", minutes)
            parameter("token", token)
            parameter("obtimezone", "local")
            header("Referer", "https://www.weather.gov/wrh/timeseries?site=${station.id}")
            header("Origin", "https://www.weather.gov")
        }.body()

        val root = json.parseToJsonElement(response).jsonObject
        val summaryObj = root["SUMMARY"]?.jsonObject
        val responseCode = summaryObj?.get("RESPONSE_CODE")?.jsonPrimitive?.intOrNull
        if (responseCode != 1) {
            val message = summaryObj?.get("RESPONSE_MESSAGE")?.jsonPrimitive?.contentOrNull
            Log.w(TAG, "Synoptic request failed for ${station.id}: $message")
            return@bestEffort null
        }

        val stationArray = root["STATION"]?.jsonArray
        val firstStation = stationArray?.firstOrNull()?.jsonObject ?: return@bestEffort null
        val obsObj = firstStation["OBSERVATIONS"]?.jsonObject ?: return@bestEffort null

        val dateTimeArray = obsObj["date_time"]?.jsonArray ?: return@bestEffort null
        val airTempArray = obsObj["air_temp_set_1"]?.jsonArray
        val weatherSummaryArray = obsObj["weather_summary_set_1d"]?.jsonArray
        val weatherCondArray = obsObj["weather_condition_set_1d"]?.jsonArray

        val stationName = firstStation["NAME"]?.jsonPrimitive?.content ?: station.name
        val observationList = mutableListOf<NwsApi.Observation>()

        for (i in 0 until dateTimeArray.size) {
            val dateTimeStr = dateTimeArray[i].jsonPrimitive.content
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

        if (observationList.isEmpty()) return@bestEffort null

        val latest = observationList.last()
        val historical = observationList.dropLast(1)
        ObservationBundle(station, latest, historical)
    }
```

### 3. Update `fetchObservationBundles`
In [DesktopWeatherService.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherService.kt#L325-L352):
Change the `.map` over stations to `.mapIndexed` and check if the station observations are missing or stale (older than 1 hour).

```kotlin
        val deferreds = stations.take(MAX_OBSERVATION_STATIONS).mapIndexed { index, station ->
            async {
                val historical = bestEffort("historical observations ${station.id}") {
                    nwsApi.getObservations(station.id, start.toString(), end.toString()).also { obs ->
                        Log.i(TAG, "historical observations: station=${station.id} type=${station.type} count=${obs.size}")
                    }
                }.orEmpty()
                
                val latest = if (historical.isNotEmpty()) {
                    bestEffort("latest observation ${station.id}") {
                        nwsApi.getLatestObservationDetailed(station.id)
                    }
                } else {
                    null
                }

                val oneHourAgo = System.currentTimeMillis() - 1 * 60 * 60 * 1000L
                val isStale = latest == null || runCatching { ZonedDateTime.parse(latest.timestamp).toInstant().toEpochMilli() }.getOrDefault(0L) < oneHourAgo

                if (index < 2 && isStale) {
                    Log.i(TAG, "NWS API observations for ${station.id} are stale or missing. Querying Synoptic fallback...")
                    val synopticBundle = fetchSynopticObservations(station, historyDays)
                    if (synopticBundle != null) {
                        return@async synopticBundle
                    }
                }

                if (historical.isNotEmpty()) {
                    ObservationBundle(station, latest, historical)
                } else {
                    null
                }
            }
        }
```

---

## Verification Plan
1. **Compilation**: Run `./gradlew :desktop:compileKotlin` to verify the code compiles without errors.
2. **Unit / Integration Testing**:
   - Write or update a test in `desktop/src/test/kotlin/com/weatherwidget/desktop/` to mock NWS API returning stale observations, and verify it falls back to the Synoptic endpoint.
3. **Execution & Logs Audit**:
   - Run the desktop app using `./gradlew :desktop:run`.
   - Verify in the logs that if NWS is stale, Synoptic queries succeed and construct a valid `ObservationBundle` for KNUQ.
