package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
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
import org.junit.experimental.categories.Category
import org.junit.Test

/**
 * Phase 3b of plans/260909-nws-fetch-unification.md: the shared NWS forecast fetch orchestration
 * must return the same raw pieces both platforms previously fetched independently, and must degrade
 * the gridpoints leg without failing the forecast.
 */
@Category(ShortDuration::class)
class NwsForecastFetchTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val pointsJson = """
        {
          "properties": {
            "gridId": "MTR",
            "gridX": 85,
            "gridY": 105,
            "forecast": "https://api.weather.gov/gridpoints/MTR/85,105/forecast",
            "observationStations": "https://api.weather.gov/gridpoints/MTR/85,105/stations"
          }
        }
    """.trimIndent()

    private val forecastJson = """
        {
          "properties": {
            "periods": [
              {
                "name": "Today",
                "temperature": 65,
                "temperatureUnit": "F",
                "shortForecast": "Sunny",
                "isDaytime": true,
                "startTime": "2026-09-09T08:00:00-07:00",
                "endTime": "2026-09-09T18:00:00-07:00"
              }
            ]
          }
        }
    """.trimIndent()

    private val hourlyJson = """
        {
          "properties": {
            "periods": [
              {
                "startTime": "2026-09-09T14:00:00+00:00",
                "temperature": 70,
                "temperatureUnit": "F",
                "shortForecast": "Sunny",
                "probabilityOfPrecipitation": { "value": 5 }
              }
            ]
          }
        }
    """.trimIndent()

    private val gridpointsJson = """
        {
          "properties": {
            "skyCover": {
              "values": [ { "validTime": "2026-09-09T14:00:00+00:00/PT1H", "value": 42 } ]
            }
          }
        }
    """.trimIndent()

    private fun apiWith(gridpointsStatus: HttpStatusCode = HttpStatusCode.OK): NwsApi {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val path = request.url.encodedPath
                    val (body, status) = when {
                        path.endsWith("/forecast/hourly") -> hourlyJson to HttpStatusCode.OK
                        path.endsWith("/forecast") -> forecastJson to HttpStatusCode.OK
                        path.contains("/points/") -> pointsJson to HttpStatusCode.OK
                        gridpointsStatus == HttpStatusCode.OK -> gridpointsJson to HttpStatusCode.OK
                        else -> "boom" to gridpointsStatus
                    }
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            install(ContentNegotiation) { json(this@NwsForecastFetchTest.json) }
        }
        return NwsApi(client, json)
    }

    @Test
    fun `fetch merges gridpoint sky cover onto the hourly periods`() = runBlocking {
        val grid = apiWith().getGridPoint(37.42, -122.08)
        val bundle = NwsForecastFetch.fetch(apiWith(), grid)

        assertEquals(1, bundle.forecastPeriods.size)
        assertEquals("Today", bundle.forecastPeriods[0].name)
        assertEquals(1, bundle.rawHourlyPeriods.size)
        assertNull(bundle.rawHourlyPeriods[0].cloudCover)
        // Sky cover was merged onto the matching hour.
        assertEquals(42, bundle.hourlyPeriods[0].cloudCover)
        assertNull(bundle.gridpointsFailure)
    }

    @Test
    fun `gridpoints failure degrades to an empty bundle without failing the fetch`() = runBlocking {
        val grid = apiWith().getGridPoint(37.42, -122.08)
        val bundle = NwsForecastFetch.fetch(apiWith(HttpStatusCode.InternalServerError), grid)

        assertNotNull(bundle.gridpointsFailure)
        assertTrue(bundle.gridpoints.skyCoverByHour.isEmpty())
        assertTrue(bundle.gridpoints.qpfIntervals.isEmpty())
        // The forecast legs still came through.
        assertEquals(1, bundle.forecastPeriods.size)
        assertEquals(1, bundle.hourlyPeriods.size)
        assertNull(bundle.hourlyPeriods[0].cloudCover)
    }
}
