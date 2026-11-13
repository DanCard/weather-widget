package com.weatherwidget.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.Collections
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.shared.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

/**
 * Tests the Silurian "API²" client (beta.weather.silurian.ai/api/v1): split hourly/daily endpoints,
 * X-API-KEY auth, imperial units (°F, precip in inches -> mm), and weather_code -> condition mapping.
 */
@Category(ShortDuration::class)
class SilurianApiTest {
    private lateinit var json: Json

    @Before
    fun setup() {
        json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    private val hourlyResponse = """
        {
          "latitude": 37.77, "longitude": -122.42,
          "forecast_time": "2026-03-02T13:00:00Z",
          "timezone": "UTC", "utc_offset": 0,
          "units": { "temperature": "°F", "precipitation_accumulation": "in" },
          "hourly": [
            {
              "timestamp": "2026-03-02T14:00:00Z",
              "temperature": 74.0,
              "weather_code": "rain",
              "precipitation_probability": 60,
              "precipitation_accumulation": 0.05,
              "cloud_cover": 88
            }
          ]
        }
    """.trimIndent()

    private val dailyResponse = """
        {
          "latitude": 37.77, "longitude": -122.42,
          "forecast_time": "2026-03-02T13:00:00Z",
          "timezone": "UTC", "utc_offset": 0,
          "units": { "temperature": "°F", "precipitation_accumulation": "in" },
          "daily": [
            {
              "timestamp": "2026-03-02",
              "max_temperature": 75.0,
              "min_temperature": 50.0,
              "weather_code": "partly-cloudy-day",
              "precipitation_probability": 45,
              "precipitation_accumulation": 0.25
            }
          ]
        }
    """.trimIndent()

    private fun client(captured: MutableList<HttpRequestData>): HttpClient {
        val mockEngine = MockEngine { request ->
            captured += request
            val body = if (request.url.encodedPath.contains("/forecast/daily")) dailyResponse else hourlyResponse
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(mockEngine) { install(ContentNegotiation) { json(json) } }
    }

    @Test
    fun `getForecast parses API2 hourly and daily responses`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val result = SilurianApi(client(requests), json) { "test-api-key" }.getForecast(37.7749, -122.4194)

        // Hourly: temperature stays °F, precip converts inches -> mm (0.05in * 25.4), code -> condition.
        assertEquals(1, result.hourly.size)
        assertEquals(1772460000000L, result.hourly[0].dateTime)
        assertEquals(74.0f, result.hourly[0].temperature)
        assertEquals("Rain", result.hourly[0].condition)
        assertEquals(60, result.hourly[0].precipProbability)
        assertEquals(1.27f, result.hourly[0].precipAmountMm!!, 0.001f)
        assertEquals(88, result.hourly[0].cloudCover)
        assertEquals("SILURIAN", result.hourly[0].source)

        // Daily: max/min map to high/low; precip 0.25in -> 6.35mm; code -> condition.
        assertEquals(1, result.daily.size)
        assertEquals("2026-03-02", result.daily[0].date)
        assertEquals(75.0f, result.daily[0].highTemp)
        assertEquals(50.0f, result.daily[0].lowTemp)
        assertEquals("Partly Cloudy", result.daily[0].condition)
        assertEquals(45, result.daily[0].precipProbability)
        assertEquals(6.35f, result.daily[0].precipAmountMm!!, 0.001f)

        // API² has no current-observation endpoint.
        assertNull(result.providerCurrentTemp)
        assertNull(result.providerCurrentObservedAt)
    }

    @Test
    fun `getForecast hits both endpoints with X-API-KEY, imperial units, and include_past`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        SilurianApi(client(requests), json) { "test-api-key" }.getForecast(37.7749, -122.4194)

        val hourly = requests.single { it.url.encodedPath.endsWith("/forecast/hourly") }
        val daily = requests.single { it.url.encodedPath.endsWith("/forecast/daily") }

        // Auth header on every call.
        listOf(hourly, daily).forEach { req ->
            assertEquals("test-api-key", req.headers["X-API-KEY"])
            assertEquals("imperial", req.url.parameters["units"])
            assertEquals("37.7749", req.url.parameters["latitude"])
            assertEquals("-122.4194", req.url.parameters["longitude"])
        }
        // Past hours requested only on the hourly endpoint (drives the actual line).
        assertEquals("true", hourly.url.parameters["include_past"])
        assertTrue(hourly.url.host == "earth.weather.silurian.ai")
    }

    @After
    fun restoreLog() = Log.resetToDefault()

    /** A client answering the hourly and daily endpoints with the given bodies. */
    private fun clientFor(
        hourly: String = """{"hourly": []}""",
        daily: String = """{"daily": []}""",
        status: HttpStatusCode = HttpStatusCode.OK,
        captured: MutableList<HttpRequestData> = Collections.synchronizedList(mutableListOf()),
    ): HttpClient {
        val engine = MockEngine { request ->
            captured += request
            respond(
                content = if (request.url.encodedPath.endsWith("/forecast/daily")) daily else hourly,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(engine) { install(ContentNegotiation) { json(json) } }
    }

    private fun api(client: HttpClient) = SilurianApi(client, json) { "test-api-key" }

    @Test
    fun `daily only skips the hourly endpoint and hourly only skips the daily one`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val api = SilurianApi(client(requests), json) { "test-api-key" }

        val dailyOnly = api.getForecast(37.7749, -122.4194, ForecastParts.DAILY)
        assertEquals(listOf("/api/v1/forecast/daily"), requests.map { it.url.encodedPath })
        assertEquals(1, dailyOnly.daily.size)
        assertEquals(0, dailyOnly.hourly.size)

        requests.clear()
        val hourlyOnly = api.getForecast(37.7749, -122.4194, ForecastParts.HOURLY)
        assertEquals(listOf("/api/v1/forecast/hourly"), requests.map { it.url.encodedPath })
        assertEquals(1, hourlyOnly.hourly.size)
        assertEquals(0, hourlyOnly.daily.size)
    }

    @Test
    fun `both endpoints ask for local time and only hourly asks for past hours`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        SilurianApi(client(requests), json) { "test-api-key" }.getForecast(37.7749, -122.4194)

        val daily = requests.single { it.url.encodedPath.endsWith("/forecast/daily") }
        val hourly = requests.single { it.url.encodedPath.endsWith("/forecast/hourly") }
        assertEquals("local", daily.url.parameters["timezone"])
        assertEquals("local", hourly.url.parameters["timezone"])
        assertNull(daily.url.parameters["include_past"])
        assertEquals("GET", hourly.method.value)
    }

    @Test
    fun `a timestamp with no offset is local time at the reply's utc_offset`() = runTest {
        // 14:00 local at UTC-8 is 22:00Z; the same digits with an explicit offset ignore utc_offset.
        val hourly = """
            {"utc_offset": -28800, "hourly": [
              {"timestamp": "2026-03-02T14:00:00", "temperature": 60.0},
              {"timestamp": "2026-03-02T15:00:00-05:00", "temperature": 61.0}
            ]}
        """.trimIndent()
        val result = api(clientFor(hourly = hourly)).getForecast(37.0, -122.0)

        assertEquals(listOf(1772488800000L, 1772481600000L), result.hourly.map { it.dateTime })
    }

    @Test
    fun `an hour with an unreadable time or no temperature is dropped`() = runTest {
        val hourly = """
            {"utc_offset": 0, "hourly": [
              {"timestamp": "yesterday-ish", "temperature": 60.0},
              {"temperature": 61.0},
              {"timestamp": "2026-03-02T14:00:00Z"},
              {"timestamp": "2026-03-02T15:00:00Z", "temperature": 63.0}
            ]}
        """.trimIndent()
        val logged = mutableListOf<String>()
        Log.install { priority, _, msg, _ -> if (priority == Log.Priority.WARN) logged += msg }
        val result = api(clientFor(hourly = hourly)).getForecast(37.0, -122.0)

        assertEquals(listOf(63.0f), result.hourly.map { it.temperature })
        assertNull(result.hourly.single().precipAmountMm)
        assertNull(result.hourly.single().precipProbability)
        assertNull(result.hourly.single().cloudCover)
        assertEquals(listOf("Unparseable Silurian timestamp: yesterday-ish"), logged)
    }

    @Test
    fun `a day missing its max or min uses temperature, and is dropped with neither`() = runTest {
        val daily = """
            {"daily": [
              {"timestamp": "2026-03-02T00:00:00", "min_temperature": 50.0, "temperature": 62.0},
              {"timestamp": "2026-03-03", "max_temperature": 70.0, "temperature": 63.0},
              {"timestamp": "2026-03-04", "max_temperature": 71.0},
              {"timestamp": "2026-03-05", "min_temperature": 51.0},
              {"max_temperature": 72.0, "min_temperature": 52.0},
              {"timestamp": "2026-03-07", "max_temperature": 73.0, "min_temperature": 53.0, "weather_code": "snow"}
            ]}
        """.trimIndent()
        val result = api(clientFor(daily = daily)).getForecast(37.0, -122.0)

        assertEquals(
            listOf(
                Triple("2026-03-02", 62.0f, 50.0f),
                Triple("2026-03-03", 70.0f, 63.0f),
                Triple("2026-03-07", 73.0f, 53.0f),
            ),
            result.daily.map { Triple(it.date, it.highTemp, it.lowTemp) },
        )
        assertEquals("Snow", result.daily.last().condition)
        assertEquals("Snow", result.daily.last().iconToken)
        assertNull(result.daily.last().precipAmountMm)
    }

    @Test
    fun `each weather code maps to its condition and anything else is Unknown`() = runTest {
        val codes = listOf(
            "clear-day" to "Clear", "clear-night" to "Clear",
            "partly-cloudy-day" to "Partly Cloudy", "partly-cloudy-night" to "Partly Cloudy",
            "cloudy" to "Cloudy", "rain" to "Rain", "snow" to "Snow", "hail" to "Unknown",
        )
        val rows = codes.mapIndexed { i, (code, _) ->
            """{"timestamp": "2026-03-02T${"%02d".format(i)}:00:00Z", "temperature": 60.0, "weather_code": "$code"}"""
        } + """{"timestamp": "2026-03-02T20:00:00Z", "temperature": 60.0}"""
        val result = api(clientFor(hourly = """{"hourly": [${rows.joinToString(",")}]}""")).getForecast(37.0, -122.0)

        assertEquals(codes.map { it.second } + "Unknown", result.hourly.map { it.condition })
    }

    @Test
    fun `a missing or blank key fails before any request`() = runTest {
        for (key in listOf(null, "", "  ")) {
            val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
            try {
                SilurianApi(client(requests), json) { key }.getForecast(37.0, -122.0)
                fail("expected IllegalStateException for key '$key'")
            } catch (e: IllegalStateException) {
                assertEquals("SILURIAN_API_KEY is missing.", e.message)
            }
            assertEquals(0, requests.size)
        }
    }

    @Test
    fun `a non-2xx reply throws with the status and the reply body`() = runTest {
        val client = clientFor(hourly = "quota exceeded", daily = "quota exceeded", status = HttpStatusCode.TooManyRequests)
        try {
            api(client).getForecast(37.0, -122.0, ForecastParts.HOURLY)
            fail("expected ApiAccessException")
        } catch (e: ApiAccessException) {
            assertEquals(429, e.statusCode)
            assertEquals("quota exceeded", e.detail)
            assertEquals("Silurian fetch failed (/forecast/hourly): status 429. Detail: quota exceeded", e.message)
        }
    }

    @Test
    fun `each fetch logs one cloud cover summary per endpoint, naming the noon problems`() = runTest {
        val hourly = """
            {"hourly": [
              {"timestamp": "2026-03-02T12:00:00", "temperature": 60.0, "cloud_cover": null},
              {"timestamp": "2026-03-03T12:00:00", "temperature": 60.0, "cloud_cover": 0},
              {"timestamp": "2026-03-04T12:00:00", "temperature": 60.0, "cloud_cover": 0.5},
              {"timestamp": "2026-03-05T12:00:00", "temperature": 60.0, "cloud_cover": 40},
              {"timestamp": "2026-03-05T13:00:00", "temperature": 60.0},
              {"timestamp": "2026-03-05T14:00:00", "temperature": 60.0, "cloud_cover": 0},
              {"timestamp": "2026-03-05T15:00:00", "temperature": 60.0, "cloud_cover": "thick"},
              {"temperature": 60.0, "cloud_cover": 55}
            ]}
        """.trimIndent()
        val logged = mutableListOf<String>()
        Log.install { priority, tag, msg, _ -> if (priority == Log.Priority.DEBUG && tag == "SilurianApi") logged += msg }
        api(clientFor(hourly = hourly)).getForecast(37.0, -122.0, ForecastParts.HOURLY)

        assertEquals(
            listOf(
                "cloudCoverSummary kind=hourly total=8 present=4 missing=2 zero=2 nonInt=2 " +
                    "noonIssues=2026-03-02T12:00:00=null,2026-03-03T12:00:00=0,2026-03-04T12:00:00=nonInt(0.5)",
                "cloudCoverSummary kind=daily array=absent",
            ),
            logged,
        )

        logged.clear()
        api(clientFor(daily = """{"daily": [{"timestamp": "2026-03-02", "max_temperature": 70.0, "min_temperature": 50.0, "cloud_cover": 30}]}"""))
            .getForecast(37.0, -122.0, ForecastParts.DAILY)
        assertEquals(
            listOf(
                "cloudCoverSummary kind=hourly array=absent",
                "cloudCoverSummary kind=daily total=1 present=1 missing=0 zero=0 nonInt=0 noonIssues=-",
            ),
            logged,
        )
    }
}
