package com.weatherwidget.data.remote

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
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
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.LocalDate

/**
 * [NwsForecastReply.fetch] is the NWS forecast fetch both platforms run. These tests pin what it
 * returns as the stored [RawFetch] and the diagnostic lines it logs, against one small recorded reply:
 * a single daytime period, one live hour at 14:00Z, and a raw grid that still carries 11:00-13:00Z.
 */
@Category(ShortDuration::class)
class NwsForecastReplyTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val hour = 3_600_000L
    private val t14 = Instant.parse("2026-09-09T14:00:00Z").toEpochMilli()
    private val today = LocalDate.of(2026, 9, 9)
    private val nws = WeatherSource.NWS.id

    private val pointsJson = """
        { "properties": { "gridId": "MTR", "gridX": 85, "gridY": 105,
            "forecast": "https://api.weather.gov/gridpoints/MTR/85,105/forecast" } }
    """.trimIndent()

    private val forecastJson = """
        { "properties": { "periods": [ {
            "name": "Today", "temperature": 65, "temperatureUnit": "F", "shortForecast": "Sunny",
            "isDaytime": true,
            "startTime": "2026-09-09T08:00:00-07:00", "endTime": "2026-09-09T18:00:00-07:00" } ] } }
    """.trimIndent()

    private val hourlyJson = """
        { "properties": { "periods": [ {
            "startTime": "2026-09-09T14:00:00+00:00", "temperature": 70, "temperatureUnit": "F",
            "shortForecast": "Sunny", "probabilityOfPrecipitation": { "value": 5 } } ] } }
    """.trimIndent()

    private val gridpointsJson = """
        { "properties": {
            "skyCover": { "values": [
                { "validTime": "2026-09-09T11:00:00+00:00/PT2H", "value": 90 },
                { "validTime": "2026-09-09T13:00:00+00:00/PT1H", "value": 10 },
                { "validTime": "2026-09-09T14:00:00+00:00/PT1H", "value": 42 } ] },
            "temperature": { "uom": "wmoUnit:degC", "values": [
                { "validTime": "2026-09-09T11:00:00+00:00/PT3H", "value": 20 },
                { "validTime": "2026-09-09T14:00:00+00:00/PT1H", "value": 21.11111 } ] },
            "probabilityOfPrecipitation": { "uom": "wmoUnit:percent", "values": [
                { "validTime": "2026-09-09T12:00:00+00:00/PT1H", "value": 30 } ] } } }
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
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
            install(ContentNegotiation) { json(this@NwsForecastReplyTest.json) }
        }
        return NwsApi(client, json)
    }

    private class Reply(val fetch: RawFetch, val logs: List<String>) {
        fun tags() = logs.map { it.substringBefore(' ') }
        fun line(tag: String) = logs.single { it.startsWith("$tag ") }
    }

    private fun fetch(
        parts: ForecastParts = ForecastParts.BOTH,
        nowMs: Long = t14,
        gridpointsStatus: HttpStatusCode = HttpStatusCode.OK,
    ): Reply = runBlocking {
        val logs = mutableListOf<String>()
        val raw = NwsForecastReply.fetch(
            nwsApi = apiWith(gridpointsStatus),
            latitude = 37.42,
            longitude = -122.08,
            parts = parts,
            log = { tag, message, level -> logs += "$tag $level $message" },
            today = today,
            nowMs = nowMs,
        )
        Reply(raw, logs)
    }

    private val liveHour = HourlyForecast(
        dateTime = t14,
        temperature = 70f,
        condition = "Sunny",
        precipProbability = 5,
        cloudCover = 42,
        source = nws,
        fetchedAt = t14,
    )

    @Test
    fun `the live hours are stored as NWS rows stamped with the fetch time`() {
        assertEquals(listOf(liveHour), fetch().fetch.hourly)
        assertEquals(listOf(liveHour.copy(fetchedAt = t14 + 5)), fetch(nowMs = t14 + 5).fetch.hourly)
    }

    @Test
    fun `the grid's already-elapsed hours are returned separately and carry no fetch time`() {
        fun elapsed(offsetHours: Int, cloud: Int, condition: String, precip: Int?) = HourlyForecast(
            dateTime = t14 + offsetHours * hour,
            temperature = 68f,
            condition = condition,
            precipProbability = precip,
            cloudCover = cloud,
            source = nws,
        )
        assertEquals(
            listOf(
                elapsed(-3, cloud = 90, condition = "Cloudy", precip = null),
                elapsed(-2, cloud = 90, condition = "Cloudy", precip = 30),
                elapsed(-1, cloud = 10, condition = "Clear", precip = null),
            ),
            fetch().fetch.elapsedHourly,
        )
    }

    @Test
    fun `a full fetch returns the daily row built from the forecast period`() {
        val daily = fetch().fetch.daily
        assertEquals(listOf("2026-09-09"), daily.map { it.date })
        assertEquals(65f, daily.single().highTemp)
        assertEquals("Sunny", daily.single().condition)
    }

    @Test
    fun `an hourly-only fetch returns no daily rows and logs no daily summary`() {
        val reply = fetch(parts = ForecastParts.HOURLY)
        assertEquals(emptyList<DailyForecast>(), reply.fetch.daily)
        assertEquals(listOf(liveHour), reply.fetch.hourly)
        assertEquals(3, reply.fetch.elapsedHourly.size)
        assertEquals(emptyList<String>(), reply.tags())
    }

    @Test
    fun `a full fetch logs the period summary, today's source and the batch summary`() {
        val reply = fetch()
        assertEquals(listOf("NWS_PERIOD_SUMMARY", "NWS_TODAY_SOURCE", "NWS_BATCH_SUMMARY"), reply.tags())
        assertEquals(
            "NWS_BATCH_SUMMARY DEBUG url=https://api.weather.gov/gridpoints/MTR/85,105/forecast rawCount=1 " +
                "rawLast=Today@2026-09-09T08:00:00-07:00..2026-09-09T18:00:00-07:00 temp=65 isDay=true " +
                "mappedCount=1 mappedMaxDate=2026-09-09 terminalLowOnlyPreserved=false " +
                "preservedDate=null preservedLow=null",
            reply.line("NWS_BATCH_SUMMARY"),
        )
    }

    @Test
    fun `an hour that has already started is logged as stale with its age`() {
        // 07:00 is 14:00Z in the test zone (America/Los_Angeles).
        assertEquals(
            "NWS_HOURLY_STALE DEBUG time=2026-09-09T07:00 temp=70.0 ageMin=60",
            fetch(nowMs = t14 + hour).line("NWS_HOURLY_STALE"),
        )
        assertEquals("an hour starting exactly now is not stale", false, "NWS_HOURLY_STALE" in fetch(nowMs = t14).tags())
        assertEquals("NWS_HOURLY_STALE DEBUG time=2026-09-09T07:00 temp=70.0 ageMin=0", fetch(nowMs = t14 + 1).line("NWS_HOURLY_STALE"))
    }

    @Test
    fun `a failed grid request is logged and the forecast is still returned without sky cover`() {
        val reply = fetch(gridpointsStatus = HttpStatusCode.InternalServerError)
        assertEquals(listOf(liveHour.copy(cloudCover = null)), reply.fetch.hourly)
        assertEquals(emptyList<HourlyForecast>(), reply.fetch.elapsedHourly)
        assertEquals(listOf("2026-09-09"), reply.fetch.daily.map { it.date })
        assertEquals(listOf("NWS_GRIDPOINTS_FAIL", "NWS_SKYCOVER_EMPTY"), reply.tags().take(2))
        assertEquals(true, reply.line("NWS_GRIDPOINTS_FAIL").endsWith("grid=MTR/85,105"))
        assertEquals(
            "NWS_SKYCOVER_EMPTY DEBUG grid=MTR/85,105 qpf=0 maxDays=0 minDays=0",
            reply.line("NWS_SKYCOVER_EMPTY"),
        )
    }

    @Test
    fun `the batch summary describes the last raw period and the mapped rows`() {
        val periods = listOf(
            NwsApi.ForecastPeriod("Today", "s1", "e1", 65, "F", "Sunny", isDaytime = true),
            NwsApi.ForecastPeriod("Tonight", "s2", "e2", 50, "F", "Clear", isDaytime = false),
        )
        val daily = listOf(
            DailyForecast("2026-09-10", 66f, 51f, "Sunny"),
            DailyForecast("2026-09-09", 65f, 50f, "Sunny"),
        )
        assertEquals(
            NwsForecastReply.BatchSummary(
                periodCount = 2, lastPeriodName = "Tonight", lastPeriodStart = "s2", lastPeriodEnd = "e2",
                lastPeriodTemp = 50, lastPeriodIsDaytime = false, mappedCount = 2,
                mappedMaxTargetDate = "2026-09-10", terminalLowOnlyPreserved = true,
                preservedDate = "2026-09-11", preservedLowTemp = 48f,
            ),
            NwsForecastReply.batchSummary(periods, daily, "2026-09-11" to 48f),
        )
        assertEquals(
            NwsForecastReply.BatchSummary(0, null, null, null, null, null, 0, null, false, null, null),
            NwsForecastReply.batchSummary(emptyList(), emptyList(), null),
        )
    }
}
