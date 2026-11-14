package com.weatherwidget.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant

/** NWS `updateTime` (issuance) reaches the hourly and elapsed periods. plans/261114-nws-issuance-time-prior.md */
@Category(ShortDuration::class)
class NwsIssuanceParseTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun api(body: String) = NwsApi(
        HttpClient(MockEngine) {
            engine { addHandler { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) } }
            install(ContentNegotiation) { json(json) }
        },
        json,
    )

    @Test
    fun `hourly periods carry the product's updateTime, not generatedAt`() = runTest {
        // Shape of the live 2026-11-14 probe: issued 06:46:20Z, rendered 07:26:26Z.
        val body = """
            {"properties": {"updateTime": "2026-11-14T06:46:20+00:00", "generatedAt": "2026-11-14T07:26:26+00:00",
              "periods": [{"startTime": "2026-11-14T00:00:00-08:00", "temperature": 41, "temperatureUnit": "F", "shortForecast": "Clear"}]}}
        """.trimIndent()
        val periods = api(body).getHourlyForecast(NwsApi.GridPointInfo("BOU", 63, 62, "https://example.com/forecast"))
        println("issuedAt=${periods.single().issuedAt?.let { Instant.ofEpochMilli(it) }}")
        assertEquals(Instant.parse("2026-11-14T06:46:20Z").toEpochMilli(), periods.single().issuedAt)
    }

    @Test
    fun `missing or malformed updateTime leaves issuedAt null`() = runTest {
        val body = """{"properties": {"updateTime": "nope", "periods": [{"startTime": "2026-11-14T00:00:00-08:00", "temperature": 41, "shortForecast": "Clear"}]}}"""
        assertNull(api(body).getHourlyForecast(NwsApi.GridPointInfo("BOU", 63, 62, "https://example.com/forecast")).single().issuedAt)
    }

    @Test
    fun `elapsed grid hours carry the grid's updateTime`() {
        val issued = Instant.parse("2026-11-14T06:46:20Z").toEpochMilli()
        val hour = Instant.parse("2026-11-14T07:00:00Z").toEpochMilli()
        val grid = NwsApi.GridpointsBundle(
            skyCoverByHour = emptyMap(), qpfIntervals = emptyList(),
            dailyTemperatures = NwsApi.DailyTemperatureExtremes(emptyMap(), emptyMap()),
            temperatureByHour = mapOf(hour to 40f), updateTime = issued,
        )
        val live = NwsApi.HourlyForecastPeriod(hour + 3_600_000L, "2026-11-14", 0, 41f, "Clear")
        val elapsed = NwsForecastFetch.elapsedPeriodsFromGrid(grid, listOf(live))
        assertEquals(issued, elapsed.single().issuedAt)
    }
}
