package com.weatherwidget.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.OffsetDateTime
import org.junit.Before
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class OpenWeatherMapApiTest {
    private lateinit var json: Json

    @Before
    fun setup() {
        json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
    }

    private fun createMockClient(
        currentJson: String,
        forecastJson: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val path = request.url.encodedPath
                    val content = if (path.contains("/weather")) currentJson else forecastJson
                    respond(
                        content = content,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    @Test
    fun `getForecast parses 2_5 weather and forecast responses`() =
        runBlocking {
            val currentWeatherJson =
                """
                {
                  "coord": { "lon": -122.08, "lat": 37.42 },
                  "weather": [
                    { "id": 804, "main": "Clouds", "description": "overcast clouds", "icon": "04d" }
                  ],
                  "main": {
                    "temp": 67.4,
                    "feels_like": 67.0,
                    "temp_min": 65.0,
                    "temp_max": 70.0,
                    "pressure": 1014,
                    "humidity": 60
                  },
                  "dt": 1771894800,
                  "timezone": -25200,
                  "id": 5375480,
                  "name": "Mountain View",
                  "cod": 200
                }
                """.trimIndent()

            val forecastJson =
                """
                {
                  "cod": "200",
                  "message": 0,
                  "cnt": 2,
                  "city": {
                    "id": 5375480,
                    "name": "Mountain View",
                    "coord": { "lat": 37.42, "lon": -122.08 },
                    "country": "US",
                    "timezone": -25200
                  },
                  "list": [
                    {
                      "dt": 1771894800,
                      "main": { "temp": 67.4, "temp_min": 65.0, "temp_max": 68.0 },
                      "weather": [ { "id": 500, "main": "Rain", "description": "light rain", "icon": "10d" } ],
                      "clouds": { "all": 64 },
                      "pop": 0.3,
                      "rain": { "3h": 1.2 },
                      "dt_txt": "2026-02-23 17:00:00"
                    },
                    {
                      "dt": 1771905600,
                      "main": { "temp": 70.8, "temp_min": 68.0, "temp_max": 72.0 },
                      "weather": [ { "id": 800, "main": "Clear", "description": "clear sky", "icon": "01d" } ],
                      "clouds": { "all": 10 },
                      "pop": 0.0,
                      "dt_txt": "2026-02-23 20:00:00"
                    }
                  ]
                }
                """.trimIndent()

            val api = OpenWeatherMapApi(createMockClient(currentWeatherJson, forecastJson), json) { "test-key" }
            val forecast = api.getForecast(37.42, -122.08)

            assertEquals(67.4f, forecast.providerCurrentTemp!!, 0.001f)
            assertEquals("Clouds", forecast.providerCurrentCondition)
            assertEquals(1771894800000L, forecast.providerCurrentObservedAt)

            assertEquals(1, forecast.daily.size)
            assertEquals("2026-02-23", forecast.daily[0].date)
            assertEquals(70.8f, forecast.daily[0].highTemp!!, 0.001f)
            assertEquals(67.4f, forecast.daily[0].lowTemp!!, 0.001f)
            assertEquals(30, forecast.daily[0].precipProbability)
            assertEquals(1.2f, forecast.daily[0].precipAmountMm!!, 0.001f)

            // Hourly forecasts include 3h entries + interpolated intermediate hours
            assertEquals(4, forecast.hourly.size)
            assertEquals(1771894800000L, forecast.hourly[0].dateTime)
            assertEquals(67.4f, forecast.hourly[0].temperature, 0.001f)
            assertEquals("Rain", forecast.hourly[0].condition)
            assertEquals(30, forecast.hourly[0].precipProbability)
            assertEquals(64, forecast.hourly[0].cloudCover)

            // Intermediate hour +1
            assertEquals(1771894800000L + 3600_000L, forecast.hourly[1].dateTime)
            assertEquals(67.4f + (70.8f - 67.4f) * (1f / 3f), forecast.hourly[1].temperature, 0.01f)

            // Intermediate hour +2
            assertEquals(1771894800000L + 7200_000L, forecast.hourly[2].dateTime)
            assertEquals(67.4f + (70.8f - 67.4f) * (2f / 3f), forecast.hourly[2].temperature, 0.01f)

            // Hour +3 (the second 3h point)
            assertEquals(1771905600000L, forecast.hourly[3].dateTime)
            assertEquals(70.8f, forecast.hourly[3].temperature, 0.001f)
            assertEquals("Clear", forecast.hourly[3].condition)
        }

    @Test
    fun `getForecast handles missing optional fields`() =
        runBlocking {
            val currentWeatherJson =
                """
                {
                  "main": {},
                  "weather": []
                }
                """.trimIndent()

            val forecastJson =
                """
                {
                  "cod": "200",
                  "city": { "timezone": 0 },
                  "list": [
                    {
                      "dt": 1771902000,
                      "main": { "temp": 65.0 },
                      "weather": [ { "main": "Clear" } ]
                    }
                  ]
                }
                """.trimIndent()

            val api = OpenWeatherMapApi(createMockClient(currentWeatherJson, forecastJson), json) { "test-key" }
            val forecast = api.getForecast(37.42, -122.08)

            assertNull(forecast.providerCurrentTemp)
            assertNull(forecast.providerCurrentCondition)
            assertNull(forecast.providerCurrentObservedAt)

            assertEquals(1, forecast.daily.size)
            assertEquals(1, forecast.hourly.size)
            assertNull(forecast.daily[0].precipProbability)
            assertEquals(0f, forecast.daily[0].precipAmountMm!!, 0.001f)
            assertNull(forecast.hourly[0].precipProbability)
            assertEquals(0f, forecast.hourly[0].precipAmountMm!!, 0.001f)
            assertNull(forecast.hourly[0].cloudCover)
        }

    @Test
    fun `getForecast throws ApiAccessException for 401 error`() =
        runBlocking {
            val responseJson =
                """
                {
                  "cod": 401,
                  "message": "Invalid API key. Please see https://openweathermap.org/faq#error401 for more info."
                }
                """.trimIndent()

            val api = OpenWeatherMapApi(createMockClient(responseJson, responseJson, HttpStatusCode.Unauthorized), json) { "test-key" }

            val exception =
                try {
                    api.getForecast(37.42, -122.08)
                    throw AssertionError("Expected ApiAccessException")
                } catch (e: ApiAccessException) {
                    e
                }

            assertEquals(401, exception.statusCode)
            assertEquals(responseJson, exception.detail)
            assertEquals(
                "OpenWeatherMap fetch failed: status 401. Detail: $responseJson",
                exception.message,
            )
        }

    // --- Interpolation, day grouping and request shape ------------------------------------------

    /** Epoch seconds of a local time in the city's zone (UTC-7), as OpenWeatherMap's `dt`. */
    private fun dt(day: Int, hour: Int) = OffsetDateTime.parse("2026-02-%02dT%02d:00:00-07:00".format(day, hour)).toEpochSecond()

    // Three periods three hours apart, a twelve-hour gap, then two more.
    private val fivePeriodsJson =
        """
        {
          "city": { "timezone": -25200 },
          "list": [
            { "dt": ${dt(23, 19)}, "main": { "temp": 60.0 }, "weather": [ { "main": "Clear", "icon": "01n" } ],
              "clouds": { "all": 20 }, "pop": 0.1, "visibility": 10000 },
            { "dt": ${dt(23, 22)}, "main": { "temp": 54.0 }, "weather": [ { "main": "Rain", "icon": "10n" } ],
              "clouds": { "all": 80 }, "pop": 0.7, "visibility": 4000, "rain": { "3h": 3.0 }, "snow": { "3h": 0.6 } },
            { "dt": ${dt(24, 1)}, "main": { "temp": 51.0 }, "weather": [ { "main": "Clouds", "icon": "04n" } ] },
            { "dt": ${dt(24, 13)}, "main": { "temp": 66.0 }, "weather": [ { "main": "Clear" } ],
              "clouds": { "all": 5 }, "pop": 0.0 },
            { "dt": ${dt(24, 16)}, "main": { "temp": 70.0 }, "weather": [ { "main": "Clouds", "icon": "04d" } ], "pop": 0.2 },
            { "main": { "temp": 99.0 }, "weather": [ { "main": "Clear" } ] }
          ]
        }
        """.trimIndent()

    private fun fivePeriods() = runBlocking {
        OpenWeatherMapApi(createMockClient("{}", fivePeriodsJson), json) { "test-key" }.getForecast(37.42, -122.08)
    }

    @Test
    fun `the two hours between three-hourly periods are interpolated, and a longer gap is left open`() {
        val start = dt(23, 19) * 1000L
        val rows = fivePeriods().hourly.map {
            "+${(it.dateTime - start) / 3_600_000L}h %.1f %s pop=%s mm=%.1f cloud=%s".format(
                it.temperature, it.condition, it.precipProbability, it.precipAmountMm, it.cloudCover,
            )
        }
        assertEquals(
            listOf(
                "+0h 60.0 Clear pop=10 mm=0.0 cloud=20",
                "+1h 58.0 Clear pop=30 mm=0.0 cloud=40",
                // Two thirds of the way to a rainy period the condition is already the next one's.
                "+2h 56.0 Rain pop=50 mm=0.0 cloud=60",
                // 3.0 mm rain + 0.6 mm snow over three hours is 1.2 mm in each hour.
                "+3h 54.0 Rain pop=70 mm=1.2 cloud=80",
                // The next period has no rain chance or cloud, so the last known values carry.
                "+4h 53.0 Rain pop=70 mm=1.2 cloud=80",
                "+5h 52.0 Rain pop=70 mm=1.2 cloud=80",
                "+6h 51.0 Clouds pop=null mm=0.0 cloud=null",
                // Twelve hours to the next period: nothing is invented in between.
                "+18h 66.0 Clear pop=0 mm=0.0 cloud=5",
                // A third of the way from 0% to 20% is 6.67%, which rounds to 7.
                "+19h 67.3 Clear pop=7 mm=0.0 cloud=5",
                "+20h 68.7 Clouds pop=13 mm=0.0 cloud=5",
                "+21h 70.0 Clouds pop=20 mm=0.0 cloud=null",
            ),
            rows,
        )
        assertTrue(fivePeriods().hourly.all { it.source == com.weatherwidget.data.model.WeatherSource.OPEN_WEATHER_MAP.id })
    }

    @Test
    fun `an interpolated hour never reports clearer air than its foggier neighbour`() {
        val vis = fivePeriods().hourly.map { it.visibilityMi }
        val clear = vis[0]!!
        val foggy = vis[3]!!
        assertTrue(foggy < clear)
        // +1h keeps the earlier period's value; +2h takes the lower of the two neighbours.
        assertEquals(listOf(clear, clear, foggy, foggy, foggy, foggy, null), vis.take(7))
    }

    @Test
    fun `daily rows group periods by the city's local date and take the condition nearest 1 pm`() {
        val daily = fivePeriods().daily.map {
            "${it.date} high=${it.highTemp} low=${it.lowTemp} ${it.condition} icon=${it.iconToken} " +
                "pop=${it.precipProbability} mm=%.1f".format(it.precipAmountMm)
        }
        assertEquals(
            listOf(
                // 19:00 and 22:00 local are still the 23rd; in UTC they would fall on the 24th.
                "2026-02-23 high=60.0 low=54.0 Clear icon=01n pop=70 mm=3.6",
                // The 13:00 period has no icon, so the condition stands in for it.
                "2026-02-24 high=70.0 low=51.0 Clear icon=Clear pop=20 mm=0.0",
            ),
            daily,
        )
    }

    @Test
    fun `a missing or blank API key fails before any request is made`() {
        var requests = 0
        val client = HttpClient(MockEngine) {
            engine { addHandler { requests++; respond("{}", HttpStatusCode.OK) } }
        }
        for (key in listOf(null, "", "  ")) {
            val error = try {
                runBlocking { OpenWeatherMapApi(client, json) { key }.getForecast(37.42, -122.08) }
                null
            } catch (e: IllegalStateException) {
                e
            }
            assertEquals("OPEN_WEATHER_MAP_API_KEY is missing.", error?.message)
        }
        assertEquals(0, requests)
    }

    @Test
    fun `both requests carry the coordinates, the key and imperial units`() {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    seen += request.url.encodedPath + "?" + request.url.parameters.entries()
                        .sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value.single()}" }
                    respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        runBlocking { OpenWeatherMapApi(client, json) { "test-key" }.getForecast(37.42, -122.08) }
        assertEquals(
            listOf(
                "/data/2.5/forecast?appid=test-key&lat=37.42&lon=-122.08&units=imperial",
                "/data/2.5/weather?appid=test-key&lat=37.42&lon=-122.08&units=imperial",
            ),
            seen.sorted(),
        )
    }

    private fun forecastOf(vararg periods: String) = runBlocking {
        val body = """{ "city": { "timezone": 0 }, "list": [ ${periods.joinToString(",")} ] }"""
        OpenWeatherMapApi(createMockClient("{}", body), json) { "test-key" }.getForecast(37.42, -122.08)
    }

    @Test
    fun `rain chance is rounded to the nearest percent, not truncated`() {
        // As 32-bit floats 0.53 * 100 and 0.59 * 100 land just under 53 and 59.
        val forecast = forecastOf(
            """{ "dt": 1771902000, "main": { "temp": 65.0 }, "weather": [ { "main": "Rain" } ], "pop": 0.53 }""",
            """{ "dt": 1771945200, "main": { "temp": 65.0 }, "weather": [ { "main": "Rain" } ], "pop": 0.59 }""",
            """{ "dt": 1771988400, "main": { "temp": 65.0 }, "weather": [ { "main": "Rain" } ], "pop": 0.996 }""",
        )
        assertEquals(listOf(53, 59, 100), forecast.hourly.map { it.precipProbability })
    }

    @Test
    fun `a period with no temperature is dropped and the gap it leaves is not interpolated`() {
        val t = 1771902000L
        val forecast = forecastOf(
            """{ "dt": $t, "main": { "temp": 60.0 }, "weather": [ { "main": "Clear" } ] }""",
            """{ "dt": ${t + 10800}, "main": {}, "weather": [ { "main": "Clear" } ] }""",
            """{ "dt": ${t + 21600}, "main": { "temp": 66.0 }, "weather": [ { "main": "Clear" } ] }""",
        )
        assertEquals(listOf(t * 1000 to 60f, (t + 21600) * 1000 to 66f), forecast.hourly.map { it.dateTime to it.temperature })
        assertEquals(listOf(66f to 60f), forecast.daily.map { it.highTemp to it.lowTemp })
    }
}
