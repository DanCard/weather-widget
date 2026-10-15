package com.weatherwidget.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import com.weatherwidget.test.category.ShortDuration
import java.time.Instant

/**
 * NWS's latest-METAR text file, the NWS web leg (plans/261015-nws-web-leg-from-nws-tgftp-metar-text.md).
 * Fixtures under `/nws_metar_text/` are the live files of 2026-10-15 ~12:50 PDT.
 */
@Category(ShortDuration::class)
class NwsMetarTextApiTest {
    private fun fixture(id: String) =
        checkNotNull(javaClass.getResourceAsStream("/nws_metar_text/$id.TXT")) { "missing fixture $id" }
            .bufferedReader().use { it.readText() }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `KNUQ - body temperature, report time, raw report kept`() {
        val row = (NwsMetarTextApi.parse(fixture("KNUQ"), "KNUQ") as FetchOutcome.Success).value

        assertEquals("KNUQ", row.stationId)
        assertEquals(ms("2026-10-15T19:35:00Z"), row.observedAtMillis)
        assertEquals(23f, row.temperatureCelsius)
        assertEquals(3f, row.dewpointCelsius)
        assertEquals("KNUQ 151935Z AUTO 00000KT 9SM CLR 23/03 A3011 RMK AO2", row.rawOb)
    }

    @Test
    fun `KSJC - the T-group's tenths win over the whole-degree body group`() {
        val row = (NwsMetarTextApi.parse(fixture("KSJC"), "KSJC") as FetchOutcome.Success).value

        assertEquals(22.8f, row.temperatureCelsius!!, 0.001f)
        assertEquals(2.2f, row.dewpointCelsius!!, 0.001f)
        assertEquals(ms("2026-10-15T18:53:00Z"), row.observedAtMillis)
    }

    @Test
    fun `a report without remarks still parses`() {
        val row = (NwsMetarTextApi.parse(fixture("KPAO"), "KPAO") as FetchOutcome.Success).value

        assertEquals(24f, row.temperatureCelsius)
        assertEquals(ms("2026-10-15T19:47:00Z"), row.observedAtMillis)
    }

    @Test
    fun `the report's own time group wins over the file header`() {
        // Header written later than the report (a re-post): the observation time is the report's.
        val body = "2026/10/15 19:58\nKNUQ 151935Z AUTO 00000KT 9SM CLR 23/03 A3011 RMK AO2\n"
        val row = (NwsMetarTextApi.parse(body, "KNUQ") as FetchOutcome.Success).value

        assertEquals(ms("2026-10-15T19:35:00Z"), row.observedAtMillis)
    }

    @Test
    fun `a report from the last day of the month under a header on the 1st steps back a month`() {
        val header = ms("2026-11-01T00:05:00Z")
        assertEquals(ms("2026-10-31T23:55:00Z"), NwsMetarTextApi.observedAtMillis("KNUQ 312355Z AUTO", header))
        // Same day: unchanged.
        assertEquals(ms("2026-11-01T00:00:00Z"), NwsMetarTextApi.observedAtMillis("KNUQ 010000Z AUTO", header))
    }

    @Test
    fun `no temperature is no data, never a 0 degree reading`() {
        val body = "2026/10/15 19:35\nKNUQ 151935Z AUTO 00000KT 9SM CLR A3011 RMK AO2\n"
        assertEquals(FetchOutcome.NoData, NwsMetarTextApi.parse(body, "KNUQ"))
    }

    @Test
    fun `an HTML page or another station's report is a failure`() {
        assertTrue(NwsMetarTextApi.parse("<html><body>Not Found</body></html>", "KNUQ") is FetchOutcome.Failed)
        assertTrue(NwsMetarTextApi.parse(fixture("KPAO"), "KNUQ") is FetchOutcome.Failed)
    }

    private fun apiServing(status: HttpStatusCode, body: String = "", requested: MutableList<String> = mutableListOf()) =
        NwsMetarTextApi(
            HttpClient(MockEngine { request ->
                requested += request.url.toString()
                if (status.value in 200..299) {
                    respond(body, status, headersOf(HttpHeaders.ContentType, "text/plain"))
                } else {
                    respondError(status)
                }
            }) { followRedirects = false },
        )

    @Test
    fun `fetch asks NWS's own server for the station's file, no token`() = runBlocking {
        val requested = mutableListOf<String>()
        val outcome = apiServing(HttpStatusCode.OK, fixture("KNUQ"), requested).fetchLatest("knuq")

        assertEquals(listOf("https://tgftp.nws.noaa.gov/data/observations/metar/stations/KNUQ.TXT"), requested)
        assertTrue(outcome is FetchOutcome.Success)
    }

    @Test
    fun `a station with no METAR file is no data - the 301 and the 404 behind it`() = runBlocking {
        assertEquals(FetchOutcome.NoData, apiServing(HttpStatusCode.MovedPermanently).fetchLatest("LOAC1"))
        assertEquals(FetchOutcome.NoData, apiServing(HttpStatusCode.NotFound).fetchLatest("LOAC1"))
    }

    @Test
    fun `a server error is a failure, not no data`() = runBlocking {
        val outcome = apiServing(HttpStatusCode.ServiceUnavailable).fetchLatest("KNUQ")
        assertTrue(outcome is FetchOutcome.Failed)
        assertNull((outcome as? FetchOutcome.Success)?.value)
    }
}
