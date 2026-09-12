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

    // The raw grid still carries the issuance's elapsed hours: 11:00-13:00Z sit before the first
    // live hourly period (14:00Z). temperature is degC (a PT3H run + one PT1H entry); the -100°F
    // sentinel leaks as -73.33 degC on the 10:00Z hour and must be dropped.
    private val gridpointsJson = """
        {
          "properties": {
            "skyCover": {
              "values": [
                { "validTime": "2026-09-09T11:00:00+00:00/PT2H", "value": 90 },
                { "validTime": "2026-09-09T13:00:00+00:00/PT1H", "value": 10 },
                { "validTime": "2026-09-09T14:00:00+00:00/PT1H", "value": 42 }
              ]
            },
            "temperature": {
              "uom": "wmoUnit:degC",
              "values": [
                { "validTime": "2026-09-09T10:00:00+00:00/PT1H", "value": -73.33333 },
                { "validTime": "2026-09-09T11:00:00+00:00/PT3H", "value": 20 },
                { "validTime": "2026-09-09T14:00:00+00:00/PT1H", "value": 21.11111 },
                { "validTime": "not-a-time", "value": 5 }
              ]
            },
            "probabilityOfPrecipitation": {
              "uom": "wmoUnit:percent",
              "values": [ { "validTime": "2026-09-09T12:00:00+00:00/PT1H", "value": 30 } ]
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
    fun `grid temperature series expands durations, converts degC, drops sentinel and malformed`() = runBlocking {
        val grid = apiWith().getGridPoint(37.42, -122.08)
        val bundle = NwsForecastFetch.fetch(apiWith(), grid)
        val byHour = bundle.gridpoints.temperatureByHour

        val h = 3_600_000L
        val t11 = java.time.Instant.parse("2026-09-09T11:00:00Z").toEpochMilli()
        // PT3H run from 11:00Z expands to 11/12/13; 14:00Z is its own entry; 10:00Z sentinel gone.
        assertEquals(setOf(t11, t11 + h, t11 + 2 * h, t11 + 3 * h), byHour.keys)
        assertEquals(68f, byHour.getValue(t11), 0.01f)
        assertEquals(70f, byHour.getValue(t11 + 3 * h), 0.01f)
        assertEquals(mapOf(t11 + h to 30), bundle.gridpoints.precipProbabilityByHour)
    }

    @Test
    fun `elapsed periods are the grid hours before the first live hour, with sky cover and condition`() = runBlocking {
        val grid = apiWith().getGridPoint(37.42, -122.08)
        val bundle = NwsForecastFetch.fetch(apiWith(), grid)

        val h = 3_600_000L
        val t11 = java.time.Instant.parse("2026-09-09T11:00:00Z").toEpochMilli()
        val elapsed = bundle.elapsedHourlyPeriods
        // 14:00Z is the live start and is NOT elapsed, whatever the grid says about it.
        assertEquals(listOf(t11, t11 + h, t11 + 2 * h), elapsed.map { it.startTime })
        assertEquals(listOf(68f, 68f, 68f), elapsed.map { it.temperature })
        // Sky cover merged per hour (PT2H run of 90 then 10) and the condition follows its band.
        assertEquals(listOf(90, 90, 10), elapsed.map { it.cloudCover })
        assertEquals(listOf("Cloudy", "Cloudy", "Clear"), elapsed.map { it.shortForecast })
        assertEquals(listOf(null, 30, null), elapsed.map { it.precipProbability })
        // The live list is untouched by the grid's extra hours.
        assertEquals(1, bundle.hourlyPeriods.size)
    }

    @Test
    fun `no live periods means no elapsed periods`() {
        val bundle = NwsApi.GridpointsBundle(
            skyCoverByHour = emptyMap(),
            qpfIntervals = emptyList(),
            dailyTemperatures = NwsApi.DailyTemperatureExtremes(emptyMap(), emptyMap()),
            temperatureByHour = mapOf(1_000L to 60f),
        )
        assertTrue(NwsForecastFetch.elapsedPeriodsFromGrid(bundle, emptyList()).isEmpty())
    }

    @Test
    fun `gridpoints failure degrades to an empty bundle without failing the fetch`() = runBlocking {
        val grid = apiWith().getGridPoint(37.42, -122.08)
        val bundle = NwsForecastFetch.fetch(apiWith(HttpStatusCode.InternalServerError), grid)

        assertNotNull(bundle.gridpointsFailure)
        assertTrue(bundle.gridpoints.skyCoverByHour.isEmpty())
        assertTrue(bundle.gridpoints.qpfIntervals.isEmpty())
        assertTrue(bundle.elapsedHourlyPeriods.isEmpty())
        // The forecast legs still came through.
        assertEquals(1, bundle.forecastPeriods.size)
        assertEquals(1, bundle.hourlyPeriods.size)
        assertNull(bundle.hourlyPeriods[0].cloudCover)
    }
}
