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
    fun `full refresh keeps hourly daily forecast and adds five minute history`() = runTest {
        val hour = Instant.now().truncatedTo(ChronoUnit.HOURS)
        val past = hour.minus(1, ChronoUnit.HOURS)
        val future = hour.plus(1, ChronoUnit.HOURS)
        val latestFiveMinute = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES)
        val capturedStarts = mutableListOf<String?>()
        val capturedEnds = mutableListOf<String?>()
        val capturedHourlyFields = mutableListOf<String?>()
        val capturedTimesteps = mutableListOf<List<String>>()
        var timelineCalls = 0
        val engine = MockEngine { request ->
            timelineCalls++
            capturedStarts += request.url.parameters["startTime"]
            capturedEnds += request.url.parameters["endTime"]
            capturedHourlyFields += request.url.parameters["fields"]
            val timesteps = request.url.parameters.getAll("timesteps").orEmpty()
            capturedTimesteps += timesteps
            val body = if (timesteps == listOf("5m")) {
                fiveMinuteTimelineJson(latestFiveMinute.minus(5, ChronoUnit.MINUTES), latestFiveMinute)
            } else {
                combinedTimelineJson(past, future, hour)
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
            assertEquals(2, timelineCalls)
            assertEquals(setOf(listOf("1h", "1d"), listOf("5m")), capturedTimesteps.toSet())
            assertEquals(2, capturedStarts.size)
            val forecastIndex = capturedTimesteps.indexOf(listOf("1h", "1d"))
            val fiveMinuteIndex = capturedTimesteps.indexOf(listOf("5m"))
            assertEquals("nowMinus23h", capturedStarts[forecastIndex])
            val fiveMinuteStart = Instant.parse(capturedStarts[fiveMinuteIndex])
            val fiveMinuteEnd = Instant.parse(capturedEnds[fiveMinuteIndex])
            assertEquals(23L, ChronoUnit.HOURS.between(fiveMinuteStart, fiveMinuteEnd))
            assertEquals(0L, Math.floorMod(fiveMinuteEnd.epochSecond, 5 * 60L))
            assertTrue(capturedHourlyFields.all { it.orEmpty().contains("cloudBase") })
            assertTrue(capturedHourlyFields.all { it.orEmpty().contains("cloudCeiling") })
            assertEquals(
                setOf(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID),
                result.rawObservations.map { it.stationId }.toSet(),
            )
            assertEquals(2, result.rawObservations.size)
            assertEquals(68f, result.providerCurrentTemp!!, 0.01f)
            assertEquals(latestFiveMinute.toEpochMilli(), result.providerCurrentObservedAt)
            val history = result.rawObservations.maxBy { it.timestamp }
            assertEquals(1_609, history.cloudEnvelopeBaseMeters)
            assertEquals(4_828, history.cloudEnvelopeTopMeters)
            assertEquals(CloudVerticalKind.TOTAL_ENVELOPE, history.cloudVerticalKind)
        } finally {
            service.close()
        }
    }

    @Test
    fun `observations only refresh returns one hour five minute history`() = runTest {
        val latest = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val requestedPaths = mutableListOf<String>()
        val requestedStarts = mutableListOf<String?>()
        val requestedEnds = mutableListOf<String?>()
        val requestedTimesteps = mutableListOf<List<String>>()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            requestedStarts += request.url.parameters["startTime"]
            requestedEnds += request.url.parameters["endTime"]
            requestedTimesteps += request.url.parameters.getAll("timesteps").orEmpty()
            respond(
                content = fiveMinuteTimelineJson(latest.minus(5, ChronoUnit.MINUTES), latest),
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

            assertEquals(listOf("/v4/timelines"), requestedPaths)
            val requestedStart = Instant.parse(requestedStarts.single())
            val requestedEnd = Instant.parse(requestedEnds.single())
            assertEquals(1L, ChronoUnit.HOURS.between(requestedStart, requestedEnd))
            assertEquals(0L, Math.floorMod(requestedEnd.epochSecond, 5 * 60L))
            assertEquals(listOf(listOf("5m")), requestedTimesteps)
            assertEquals(
                listOf(
                    TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID,
                    TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID,
                ),
                result.rawObservations.map { it.stationId },
            )
            assertTrue(result.hourly.isEmpty())
        } finally {
            service.close()
        }
    }

    private fun combinedTimelineJson(past: Instant, future: Instant, day: Instant) =
        """{"data":{"timelines":[
        {"timestep":"1d","intervals":[
            {"startTime":"$day","values":{"temperatureMax":72.0,"temperatureMin":58.0,"weatherCode":1101}}
        ]},
        {"timestep":"1h","intervals":[
            {"startTime":"$past","values":{"temperature":64.0,"weatherCode":1101,"cloudCover":72,"cloudBase":1.0,"cloudCeiling":3.0}},
            {"startTime":"$future","values":{"temperature":70.0,"weatherCode":1000,"cloudCover":10}}
        ]}]}}""".trimIndent()

    private fun fiveMinuteTimelineJson(first: Instant, latest: Instant) =
        """{"data":{"timelines":[{"timestep":"5m","intervals":[
            {"startTime":"$first","values":{"temperature":67.5,"weatherCode":1101,"cloudCover":54,"cloudBase":0.5,"cloudCeiling":2.0}},
            {"startTime":"$latest","values":{"temperature":68.0,"weatherCode":1101,"cloudCover":56,"cloudBase":1.0,"cloudCeiling":3.0}}
        ]}]}}""".trimIndent()
}
