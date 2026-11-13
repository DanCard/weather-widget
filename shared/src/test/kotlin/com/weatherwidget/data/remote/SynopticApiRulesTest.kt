package com.weatherwidget.data.remote

import com.weatherwidget.data.model.StationType
import com.weatherwidget.shared.observations.MetarRawSkyParser
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Exact outcomes for the rules in [SynopticApi] that the fixture tests run through without pinning:
 * timestamp offsets, what one station entry becomes, unit conversions, the request each fetch sends,
 * and the token retry. plans/261112-test-suite-helpfulness-audit.md
 */
@Category(ShortDuration::class)
class SynopticApiRulesTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun station(text: String) = json.parseToJsonElement(text).jsonObject

    @Test
    fun `an offset without a colon gains one, and every other timestamp is left alone`() {
        assertEquals("2026-07-13T18:05:00+00:00", SynopticApi.parseTimestampToIsoString("2026-07-13T18:05:00+0000"))
        assertEquals("2026-07-13T11:05:00-07:00", SynopticApi.parseTimestampToIsoString("2026-07-13T11:05:00-0700"))
        for (unchanged in listOf(
            "2026-07-13T18:05:00Z",
            "2026-07-13T18:05:00z",
            "2026-07-13T18:05:00+00:00",
            "2026-07-13T11:05:00-07:00",
            "2026-07-13T18:05:00",
            "2026-07-13",
            "+0000",
        )) {
            assertEquals(unchanged, SynopticApi.parseTimestampToIsoString(unchanged))
        }
    }

    @Test
    fun `a station entry becomes one observation per reading that has a temperature`() {
        val metar = "KPAO 131755Z 00000KT 10SM FEW020 BKN050 22/12 A2990 RMK AO2"
        val observations = SynopticApi.parseStationObservations(
            station(
                """
                {
                  "NAME": "Palo Alto Airport",
                  "OBSERVATIONS": {
                    "date_time": ["2026-07-13T17:00:00+0000", "2026-07-13T17:20:00Z", "2026-07-13T17:40:00Z",
                                  "2026-07-13T17:55:00Z", "2026-07-13T18:00:00Z"],
                    "air_temp_set_1": [21.5, null, 10.0, 22.0, 23.5],
                    "weather_summary_set_1d": ["Mostly Cloudy", "Fair", null, null, "haze"],
                    "weather_condition_set_1d": [null, null, "Fair", null, null],
                    "metar_set_1": [null, null, null, "$metar", ""],
                    "cloud_layer_1_set_1d": [null, null, null, null, {"sky_condition": "scattered", "height_agl": 900.0}],
                    "cloud_layer_2_set_1d": [null, null, null, null, {"sky_condition": "mystery", "height_agl": 1500.0}],
                    "cloud_layer_3_set_1d": [null, null, null, null, {"sky_condition": "overcast"}]
                  },
                  "QC": {"air_temp_set_1": [null, null, [105], [], null]}
                }
                """.trimIndent(),
            ),
            stationNameFallback = "KPAO",
        )

        val metarLayers = MetarRawSkyParser.layersFrom(metar)
        assertEquals(listOf("FEW", "BKN"), metarLayers.map { it.amount })
        assertEquals(
            listOf(
                // The summary alone gives one layer with no height; the offset gains its colon.
                NwsApi.Observation(
                    timestamp = "2026-07-13T17:00:00+00:00", temperatureCelsius = 21.5f, textDescription = "Mostly Cloudy",
                    stationName = "Palo Alto Airport", cloudLayers = listOf(NwsApi.CloudLayer("BKN", null)),
                ),
                // 17:20 has no temperature and is skipped. 17:40 failed QC and takes the condition series.
                NwsApi.Observation(
                    timestamp = "2026-07-13T17:40:00Z", temperatureCelsius = 10.0f, textDescription = "Fair",
                    stationName = "Palo Alto Airport", qcFailed = true, cloudLayers = listOf(NwsApi.CloudLayer("CLR", null)),
                ),
                // A raw report makes the row a METAR and supplies the layers; an empty QC list is a pass.
                NwsApi.Observation(
                    timestamp = "2026-07-13T17:55:00Z", temperatureCelsius = 22.0f, textDescription = "",
                    stationName = "Palo Alto Airport", cloudLayers = metarLayers, isMetar = true, rawMessage = metar,
                ),
                // No raw report: the layer series supply the sky; an unknown amount is dropped.
                NwsApi.Observation(
                    timestamp = "2026-07-13T18:00:00Z", temperatureCelsius = 23.5f, textDescription = "haze",
                    stationName = "Palo Alto Airport",
                    cloudLayers = listOf(NwsApi.CloudLayer("SCT", 900.0), NwsApi.CloudLayer("OVC", null)),
                    isMetar = false, rawMessage = "",
                ),
            ),
            observations,
        )
    }

    @Test
    fun `a station entry with no name uses the fallback, and one with no readings gives nothing`() {
        val unnamed = SynopticApi.parseStationObservations(
            station("""{"OBSERVATIONS": {"date_time": ["2026-07-13T17:00:00Z"], "air_temp_set_1": [20.0]}}"""),
            stationNameFallback = "KPAO",
        ).single()
        assertEquals("KPAO", unnamed.stationName)
        assertEquals("", unnamed.textDescription)
        assertEquals(emptyList<NwsApi.CloudLayer>(), unnamed.cloudLayers)
        assertNull(unnamed.rawMessage)

        val blankName = SynopticApi.parseStationObservations(
            station("""{"NAME": "  ", "OBSERVATIONS": {"date_time": ["2026-07-13T17:00:00Z"], "air_temp_set_1": [20.0]}}"""),
            stationNameFallback = "KPAO",
        ).single()
        assertEquals("KPAO", blankName.stationName)

        assertEquals(0, SynopticApi.parseStationObservations(station("""{"NAME": "x"}"""), "KPAO").size)
        assertEquals(
            0,
            SynopticApi.parseStationObservations(station("""{"OBSERVATIONS": {"air_temp_set_1": [20.0]}}"""), "KPAO").size,
        )
        assertEquals(
            0,
            SynopticApi.parseStationObservations(station("""{"OBSERVATIONS": {"date_time": ["2026-07-13T17:00:00Z"]}}"""), "KPAO").size,
        )
    }

    private val oneReading = """"OBSERVATIONS": {"date_time": ["2026-07-13T17:00:00Z"], "air_temp_set_1": [20.0]}"""

    @Test
    fun `a single-station reply is a success, no data, or a failure that names its cause`() {
        fun parse(response: String) = SynopticApi.parseSynopticTimeseries(json, response, "KPAO", "Palo Alto")

        val success = parse("""{"SUMMARY": {"RESPONSE_CODE": 1}, "STATION": [{$oneReading}]}""")
        assertEquals(
            FetchOutcome.Success(listOf(NwsApi.Observation("2026-07-13T17:00:00Z", 20.0f, "", "Palo Alto"))),
            success,
        )

        assertEquals(FetchOutcome.NoData, parse("""{"SUMMARY": {"RESPONSE_CODE": 1}}"""))
        assertEquals(FetchOutcome.NoData, parse("""{"SUMMARY": {"RESPONSE_CODE": 1}, "STATION": []}"""))
        assertEquals(
            FetchOutcome.NoData,
            parse("""{"SUMMARY": {"RESPONSE_CODE": 1}, "STATION": [{"OBSERVATIONS": {"date_time": ["2026-07-13T17:00:00Z"]}}]}"""),
        )

        assertEquals(
            FetchOutcome.Failed("synoptic: Invalid token"),
            parse("""{"SUMMARY": {"RESPONSE_CODE": 2, "RESPONSE_MESSAGE": "Invalid token"}, "STATION": [{$oneReading}]}"""),
        )
        assertEquals(FetchOutcome.Failed("synoptic: null"), parse("""{"STATION": [{$oneReading}]}"""))
        val malformed = parse("not json")
        assertTrue("$malformed", malformed is FetchOutcome.Failed && malformed.reason.startsWith("parse: "))
    }

    @Test
    fun `a radius station keeps its place in metric units and is dropped without an id, a position or a reading`() {
        val response = """
            {"SUMMARY": {"RESPONSE_CODE": 1}, "STATION": [
              {"STID": "KPAO", "NAME": "Palo Alto Airport", "LATITUDE": "37.4611", "LONGITUDE": "-122.1150",
               "DISTANCE": "10", "ELEVATION": "100", "MNET_ID": "1", $oneReading},
              {"STID": "C1234", "NAME": " ", "LATITUDE": "37.5", "LONGITUDE": "-122.2", $oneReading},
              {"STID": " ", "LATITUDE": "37.5", "LONGITUDE": "-122.2", $oneReading},
              {"LATITUDE": "37.5", "LONGITUDE": "-122.2", $oneReading},
              {"STID": "NOLAT", "LONGITUDE": "-122.2", $oneReading},
              {"STID": "NOLON", "LATITUDE": "37.5", $oneReading},
              {"STID": "BADLAT", "LATITUDE": "north", "LONGITUDE": "-122.2", $oneReading},
              {"STID": "SILENT", "LATITUDE": "37.5", "LONGITUDE": "-122.2",
               "OBSERVATIONS": {"date_time": ["2026-07-13T17:00:00Z"], "air_temp_set_1": [null]}},
              "not a station"
            ]}
        """.trimIndent()

        val stations = (SynopticApi.parseRadiusTimeseries(json, response) as FetchOutcome.Success).value

        assertEquals(
            listOf(
                NwsApi.StationInfo("KPAO", "Palo Alto Airport", 37.4611, -122.1150, StationType.OFFICIAL),
                NwsApi.StationInfo("C1234", "C1234", 37.5, -122.2, StationType.PERSONAL),
            ),
            stations.map { it.info },
        )
        // 10 miles and 100 feet; a station that reports neither sits at distance 0 with no elevation.
        assertEquals(16.09344, stations[0].distanceKm, 1e-9)
        assertEquals(30.48, stations[0].elevationMeters!!, 1e-9)
        assertEquals(0.0, stations[1].distanceKm, 0.0)
        assertNull(stations[1].elevationMeters)
        // A blank name falls back to the id on the observations too, not only on the station record.
        assertEquals(listOf("Palo Alto Airport", "C1234"), stations.map { it.observations.single().stationName })

        assertEquals(
            FetchOutcome.NoData,
            SynopticApi.parseRadiusTimeseries(json, """{"SUMMARY": {"RESPONSE_CODE": 1}, "STATION": [{"STID": "X"}]}"""),
        )
        assertEquals(FetchOutcome.NoData, SynopticApi.parseRadiusTimeseries(json, """{"SUMMARY": {"RESPONSE_CODE": 1}}"""))
    }

    private val okReply = """{"SUMMARY": {"RESPONSE_CODE": 1}, "STATION": [{"STID": "KPAO", "LATITUDE": "37.4", "LONGITUDE": "-122.1", $oneReading}]}"""

    private fun capturing(requests: MutableList<HttpRequestData>, reply: (HttpRequestData) -> String = { okReply }) =
        HttpClient(
            MockEngine { request ->
                requests += request
                respond(reply(request), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )

    @Test
    fun `the single-station request names the station, asks for UTC and full QC, and poses as the station page`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val outcome = SynopticApi(capturing(requests), json) { "tok" }.fetchSynopticObservations("KPAO", 120, "Palo Alto")

        assertTrue("$outcome", outcome is FetchOutcome.Success)
        val request = requests.single()
        assertEquals("https://api.synopticdata.com/v2/stations/timeseries", request.url.toString().substringBefore('?'))
        assertEquals("GET", request.method.value)
        assertEquals(
            mapOf(
                "STID" to "KPAO", "recent" to "120", "token" to "tok", "obtimezone" to "utc",
                "qc" to "on", "qc_checks" to "all", "qc_flags" to "on",
            ),
            request.url.parameters.names().associateWith { request.url.parameters[it] },
        )
        assertEquals("https://www.weather.gov/wrh/timeseries?site=KPAO", request.headers["Referer"])
        assertEquals("https://www.weather.gov", request.headers["Origin"])
    }

    @Test
    fun `the radius request sends the centre and radius, the parsed variables by default, and none when asked`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val api = SynopticApi(capturing(requests), json) { "tok" }

        val outcome = api.fetchRadiusTimeseries(37.4, -122.1)
        assertEquals(listOf("KPAO"), (outcome as FetchOutcome.Success).value.map { it.info.id })
        val byDefault = requests.single()
        assertEquals(
            mapOf(
                "radius" to "37.4,-122.1,25.0", "recent" to "120", "token" to "tok", "obtimezone" to "utc",
                "vars" to "air_temp,metar,cloud_layer_1,cloud_layer_2,cloud_layer_3,weather_summary,weather_condition",
                "qc" to "on", "qc_checks" to "all", "qc_flags" to "on",
            ),
            byDefault.url.parameters.names().associateWith { byDefault.url.parameters[it] },
        )
        assertEquals("https://www.weather.gov/wrh/timeseries", byDefault.headers["Referer"])
        assertEquals("https://www.weather.gov", byDefault.headers["Origin"])
        assertEquals("GET", byDefault.method.value)

        requests.clear()
        api.fetchRadiusTimeseries(37.4, -122.1, radiusMiles = 10.0, recentMinutes = 60, vars = emptyList())
        val unfiltered = requests.single()
        assertNull(unfiltered.url.parameters["vars"])
        assertEquals("37.4,-122.1,10.0", unfiltered.url.parameters["radius"])
        assertEquals("60", unfiltered.url.parameters["recent"])
    }

    private val staleToken = "0123456789abcdef0123456789abcdef"
    private val freshToken = "fedcba9876543210fedcba9876543210"

    /** A weather.gov token source that hands out [staleToken] first and [freshToken] after. */
    private fun tokenSource(fetches: MutableList<String>) =
        MesoTokenApi(
            HttpClient(
                MockEngine {
                    val token = if (fetches.isEmpty()) staleToken else freshToken
                    fetches += token
                    respond("var mesoToken = '$token';", headers = headersOf(HttpHeaders.ContentType, "text/javascript"))
                },
            ),
        )

    private val rejected = """{"SUMMARY": {"RESPONSE_CODE": 2, "RESPONSE_MESSAGE": "Invalid token"}}"""

    @Test
    fun `a failure on the weather-gov token fetches a new token and retries once, for both fetches`() = runBlocking {
        for (radius in listOf(false, true)) {
            val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
            val tokenFetches = mutableListOf<String>()
            val api = SynopticApi(
                capturing(requests) { if (it.url.parameters["token"] == freshToken) okReply else rejected },
                json,
                mesoTokenApi = tokenSource(tokenFetches),
            )

            val outcome = if (radius) api.fetchRadiusTimeseries(37.4, -122.1) else api.fetchSynopticObservations("KPAO", 120)

            assertTrue("radius=$radius $outcome", outcome is FetchOutcome.Success)
            assertEquals(listOf(staleToken, freshToken), requests.map { it.url.parameters["token"] })
            assertEquals(listOf(staleToken, freshToken), tokenFetches)
        }
    }

    @Test
    fun `a failure that the retry does not cure is returned after exactly two requests`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val api = SynopticApi(capturing(requests) { rejected }, json, mesoTokenApi = tokenSource(mutableListOf()))

        assertEquals(FetchOutcome.Failed("synoptic: Invalid token"), api.fetchSynopticObservations("KPAO", 120))
        assertEquals(2, requests.size)
    }

    @Test
    fun `a failure on a configured token is not retried, and no data is not a failure`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val configured = SynopticApi(capturing(requests) { rejected }, json) { "tok" }
        assertEquals(FetchOutcome.Failed("synoptic: Invalid token"), configured.fetchSynopticObservations("KPAO", 120))
        assertTrue(configured.fetchRadiusTimeseries(37.4, -122.1) is FetchOutcome.Failed)
        assertEquals(2, requests.size)

        requests.clear()
        val tokenFetches = mutableListOf<String>()
        val empty = SynopticApi(
            capturing(requests) { """{"SUMMARY": {"RESPONSE_CODE": 1}, "STATION": []}""" },
            json,
            mesoTokenApi = tokenSource(tokenFetches),
        )
        assertEquals(FetchOutcome.NoData, empty.fetchSynopticObservations("KPAO", 120))
        assertEquals(FetchOutcome.NoData, empty.fetchRadiusTimeseries(37.4, -122.1))
        assertEquals("no retry, so one request each", 2, requests.size)
        assertEquals(listOf(staleToken), tokenFetches)
    }

    @Test
    fun `a dropped connection is a failure that names the exception, for both fetches`() = runBlocking {
        val api = SynopticApi(HttpClient(MockEngine { throw java.io.IOException("connection reset") }), json) { "tok" }

        assertEquals(FetchOutcome.Failed("IOException: connection reset"), api.fetchSynopticObservations("KPAO", 120))
        assertEquals(FetchOutcome.Failed("IOException: connection reset"), api.fetchRadiusTimeseries(37.4, -122.1))
    }
}
