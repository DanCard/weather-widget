package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.AviationWeatherApi
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.NwsMetarTextApi
import com.weatherwidget.shared.fog.FogLevel
import com.weatherwidget.shared.fog.MorningFog
import com.weatherwidget.shared.fog.ObservedMorningFog
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The NWS history web leg ([NwsMetarHistoryWeb]) through the real [AviationWeatherApi] parse on a Ktor
 * [MockEngine], and with [NwsStationHistoryFetcher] and [ObservedMorningFog] on top: the emulator's
 * 2026-11-17 morning. KNUQ's API history stopped at 05:55 while its own METARs kept reporting `10SM CLR`;
 * with only the latest report back, KSJC (15.9 km, in fog 06:00-07:50) stood in and the NWS Today
 * column drew dense fog. plans/261117-today-fog-stale-forecast-and-nws-metar-history-fill.md
 */
@Category(ShortDuration::class)
class NwsMetarHistoryWebTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val date = LocalDate.parse("2026-11-17")
    private val siteLat = 37.4166
    private val siteLon = -122.0889
    private val knuq = NwsApi.StationInfo("KNUQ", "Mountain View, Moffett Field", 37.4161, -122.0492, StationType.OFFICIAL)
    private val now = Instant.parse("2026-11-17T17:28:35Z").toEpochMilli() // 09:28 local, the backfill
    private val windowStart = now - 72 * 3_600_000L

    private val awcFixture: String =
        checkNotNull(javaClass.getResourceAsStream("/aviationweather/KNUQ_261117.json")) { "missing fixture" }
            .bufferedReader().use { it.readText() }

    private val requests = mutableListOf<String>()

    private fun awc(body: String?): AviationWeatherApi {
        val engine = MockEngine { request ->
            requests += request.url.toString()
            if (body == null) respondError(HttpStatusCode.ServiceUnavailable)
            else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return AviationWeatherApi(HttpClient(engine), Json { ignoreUnknownKeys = true })
    }

    /** NWS's latest-METAR text file: always the 09:15 report, as the old leg returned. */
    private class LatestText : NwsMetarTextApi(HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })) {
        var asked = 0
        override suspend fun fetchLatest(stationId: String): FetchOutcome<AviationWeatherApi.MetarRow> {
            asked++
            return parse("2026/11/17 17:15\nKNUQ 171715Z AUTO 15005KT 10SM CLR 11/08 A3013 RMK AO2\n", stationId)
        }
    }

    private fun local(hhmm: String) = date.atTime(java.time.LocalTime.parse(hhmm)).atZone(zone).toInstant()

    /** KNUQ's api.weather.gov window: through 05:55 only (stale at 09:28). */
    private val knuqApi = listOf("04:35", "04:55", "05:15", "05:55").map { t ->
        val at = local(t)
        val z = java.time.format.DateTimeFormatter.ofPattern("ddHHmm").withZone(java.time.ZoneOffset.UTC).format(at)
        NwsApi.Observation(
            timestamp = at.toString(), temperatureCelsius = 6f, textDescription = "Clear", isMetar = true,
            rawMessage = "KNUQ ${z}Z AUTO 13003KT 10SM CLR 06/06 A3009 RMK AO2",
        )
    }

    /** KSJC's NWS rows (complete in the API): fog 06:00-07:50, mist 07:53, clear from 08:05. */
    private val ksjc = listOf(
        "05:55" to "10SM CLR 06/05", "06:23" to "1/2SM FG SCT000 06/05", "06:35" to "1/4SM FG BKN000 06/05",
        "06:53" to "1/4SM FG BKN000 06/06", "07:05" to "M1/4SM FG VV001 06/06", "07:30" to "1/4SM FG 06/06",
        "07:50" to "1/4SM FG 07/07", "07:53" to "4SM BR FEW000 SCT002 07/07", "08:05" to "10SM FEW028 07/07",
        "08:55" to "10SM CLR 09/08", "09:15" to "10SM FEW001 11/07",
    ).map { (t, body) ->
        val at = local(t)
        val z = java.time.format.DateTimeFormatter.ofPattern("ddHHmm").withZone(java.time.ZoneOffset.UTC).format(at)
        ObservationReading(
            stationId = "KSJC", stationName = "San Jose", timestamp = at.toEpochMilli(), temperature = 43f,
            condition = "", locationLat = siteLat, locationLon = siteLon, distanceKm = 15.9f,
            stationType = StationType.OFFICIAL, api = WeatherSource.NWS.id, isMetar = true,
            rawMetar = "KSJC ${z}Z 16004KT $body A3010",
        )
    }

    @Test
    fun `hours reach the window start, rounded up, within the gap fill's lookback`() {
        assertEquals(1, NwsMetarHistoryWeb.hoursFor(now - 60_000L, now))
        assertEquals(2, NwsMetarHistoryWeb.hoursFor(now - 3_600_001L, now))
        assertEquals(72, NwsMetarHistoryWeb.hoursFor(windowStart, now))
        assertEquals(72, NwsMetarHistoryWeb.hoursFor(now - 30 * 86_400_000L, now))
    }

    @Test
    fun `a station that publishes no METAR asks nothing`() = runBlocking {
        val personal = NwsApi.StationInfo("AW020", "personal", 37.42, -122.10, StationType.PERSONAL)
        val latest = LatestText()
        val result = NwsMetarHistoryWeb.fetchWindow(awc(awcFixture), latest, personal, siteLat, siteLon, windowStart, now)
        assertTrue(result is FetchOutcome.NoData)
        assertTrue(requests.isEmpty())
        assertEquals(0, latest.asked)
    }

    @Test
    fun `the station's METAR history comes back whole`() = runBlocking {
        val latest = LatestText()
        val rows = NwsMetarHistoryWeb.fetchWindow(awc(awcFixture), latest, knuq, siteLat, siteLon, windowStart, now).valueOrNull()!!
        println("history rows: " + rows.map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalTime() to it.rawMetar })
        assertEquals(9, rows.size)
        assertTrue(rows.all { it.stationId == "KNUQ" && it.rawMetar!!.contains("10SM CLR") })
        assertEquals(local("05:55").toEpochMilli(), rows.first().timestamp)
        assertEquals(local("08:35").toEpochMilli(), rows.last().timestamp)
        assertTrue("hours=72 asked: $requests", requests.single().contains("hours=72") && requests.single().contains("ids=KNUQ"))
        assertEquals("history answered: the latest text is not asked", 0, latest.asked)
    }

    @Test
    fun `an unanswered history falls back to the latest report`() = runBlocking {
        val latest = LatestText()
        val rows = NwsMetarHistoryWeb.fetchWindow(awc(null), latest, knuq, siteLat, siteLon, windowStart, now).valueOrNull()!!
        assertEquals(1, latest.asked)
        assertEquals(listOf(local("09:15").toEpochMilli()), rows.map { it.timestamp })
    }

    @Test
    fun `a stale station's recovered hours outrank a far station's fog`() = runBlocking {
        val latest = LatestText()
        val fetcher = NwsStationHistoryFetcher({ _, _, _ -> knuqApi }, { _, _, _ -> }) { now }
        suspend fun knuqRows(web: suspend () -> List<ObservationReading>?) =
            fetcher.fetch(knuq, 0, siteLat, siteLon, Instant.ofEpochMilli(windowStart).toString(), Instant.ofEpochMilli(now).toString(), web).rows

        // Before: the latest-text leg brings back one report (09:15); KSJC fills 06:00-07:59.
        val before = knuqRows { NwsMetarTextWeb.fetchReading(latest, knuq, siteLat, siteLon).valueOrNull() }
        val fogBefore = ObservedMorningFog.forDate(before + ksjc, date, zone, now)
        println("before: KNUQ rows=${before.size} fog=$fogBefore")
        assertEquals(FogLevel.DENSE, fogBefore?.level)

        // After: the history leg brings back 06:15-08:35; KNUQ stands for every hour.
        val after = knuqRows {
            NwsMetarHistoryWeb.fetchWindow(awc(awcFixture), latest, knuq, siteLat, siteLon, windowStart, now).valueOrNull()
        }
        val fogAfter = ObservedMorningFog.forDate(after + ksjc, date, zone, now)
        println("after: KNUQ rows=${after.size} fog=$fogAfter")
        assertEquals(12, after.size) // 4 API (04:35-05:55) + 8 METAR (06:15-08:35); 05:55 stays the API's
        assertTrue(after.filter { it.isWebFallback }.all { it.api == WeatherSource.NWS.id })
        assertEquals(MorningFog.NONE, fogAfter)
    }
}
