package com.weatherwidget.desktop

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.shared.actuals.TomorrowIoActuals
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TomorrowIoDesktopServiceTest {

    @Test
    fun `full refresh returns recent history and realtime with separate provenance`() = runTest {
        val hour = Instant.now().truncatedTo(ChronoUnit.HOURS)
        val past = hour.minus(1, ChronoUnit.HOURS)
        val future = hour.plus(1, ChronoUnit.HOURS)
        val realtime = hour.plus(20, ChronoUnit.MINUTES)
        val capturedStarts = mutableListOf<String?>()
        val capturedHourlyFields = mutableListOf<String?>()
        val engine = MockEngine { request ->
            val body = when {
                request.url.encodedPath == "/v4/weather/realtime" -> realtimeJson(realtime)
                request.url.parameters["timesteps"] == "1h" -> {
                    capturedStarts += request.url.parameters["startTime"]
                    capturedHourlyFields += request.url.parameters["fields"]
                    hourlyJson(past, future)
                }
                else -> dailyJson(hour)
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val service = DesktopWeatherService(
            latitude = 37.42,
            longitude = -122.08,
            weatherSource = WeatherSource.TOMORROW_IO.id,
            apiKeys = mapOf(WeatherSource.TOMORROW_IO.id to "test-key"),
            injectedHttpClient = HttpClient(engine),
        )

        try {
            val result = service.fetchForecast()

            // Covers the whole elapsed local day so a first-time fetch at a new site still gets
            // today's overnight minimum — see TomorrowIoApi's startTime comment.
            assertEquals(listOf("nowMinus23h"), capturedStarts)
            assertTrue(capturedHourlyFields.single().orEmpty().contains("cloudBase"))
            assertTrue(capturedHourlyFields.single().orEmpty().contains("cloudCeiling"))
            assertEquals(
                setOf(
                    TomorrowIoActuals.RECENT_HISTORY_STATION_ID,
                    TomorrowIoActuals.REALTIME_STATION_ID,
                ),
                result.rawObservations.map { it.stationId }.toSet(),
            )
            assertEquals(68f, result.providerCurrentTemp!!, 0.01f)
            assertEquals(realtime.toEpochMilli(), result.providerCurrentObservedAt)
            val history = result.rawObservations.single { it.stationId == TomorrowIoActuals.RECENT_HISTORY_STATION_ID }
            assertEquals(1_609, history.cloudEnvelopeBaseMeters)
            assertEquals(4_828, history.cloudEnvelopeTopMeters)
            assertEquals(CloudVerticalKind.TOTAL_ENVELOPE, history.cloudVerticalKind)
            val current = result.rawObservations.single { it.stationId == TomorrowIoActuals.REALTIME_STATION_ID }
            assertEquals(3_219, current.cloudEnvelopeBaseMeters)
            assertEquals(6_437, current.cloudEnvelopeTopMeters)
            assertEquals(CloudVerticalKind.TOTAL_ENVELOPE, current.cloudVerticalKind)
        } finally {
            service.close()
        }
    }

    @Test
    fun `observations only refresh returns realtime without timeline history`() = runTest {
        val realtime = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val requestedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            respond(
                content = realtimeJson(realtime),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val service = DesktopWeatherService(
            latitude = 37.42,
            longitude = -122.08,
            weatherSource = WeatherSource.TOMORROW_IO.id,
            apiKeys = mapOf(WeatherSource.TOMORROW_IO.id to "test-key"),
            injectedHttpClient = HttpClient(engine),
        )

        try {
            val result = service.fetchObservationsOnly(recentOnly = true)

            assertEquals(listOf("/v4/weather/realtime"), requestedPaths)
            assertEquals(listOf(TomorrowIoActuals.REALTIME_STATION_ID), result.rawObservations.map { it.stationId })
            assertTrue(result.hourly.isEmpty())
        } finally {
            service.close()
        }
    }

    private fun hourlyJson(past: Instant, future: Instant) =
        """{"data":{"timelines":[{"intervals":[
            {"startTime":"$past","values":{"temperature":64.0,"weatherCode":1101,"cloudCover":72,"cloudBase":1.0,"cloudCeiling":3.0}},
            {"startTime":"$future","values":{"temperature":70.0,"weatherCode":1000,"cloudCover":10}}
        ]}]}}""".trimIndent()

    private fun dailyJson(day: Instant) =
        """{"data":{"timelines":[{"intervals":[
            {"startTime":"$day","values":{"temperatureMax":72.0,"temperatureMin":58.0,"weatherCode":1101}}
        ]}]}}""".trimIndent()

    private fun realtimeJson(time: Instant) =
        """{"data":{"time":"$time","values":{"temperature":68.0,"weatherCode":1101,"cloudCover":56,"cloudBase":2.0,"cloudCeiling":4.0}}}"""
}
