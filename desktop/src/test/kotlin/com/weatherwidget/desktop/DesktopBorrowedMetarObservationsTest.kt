package com.weatherwidget.desktop

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Desktop drew no actual temperature curve for Open-Meteo. `fetchObservationsOnly` listed the
 * forecast-only sources under a branch that logged "no current-only desktop path is defined" and
 * returned an empty [RawFetch] — correct while those sources had no actuals, and the whole bug once
 * they began borrowing METAR. The live database showed it plainly: 51,991 NWS rows, zero METAR rows,
 * and not one `METAR_*` log line.
 */
@Category(ShortDuration::class)
class DesktopBorrowedMetarObservationsTest {

    private fun engine() = MockEngine { request ->
        val path = request.url.encodedPath
        val content = when {
            path.endsWith("/stationinfo") -> stationInfoJson()
            path.endsWith("/metar") -> metarJson()
            else -> "[]"
        }
        respond(
            content = content,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    @Test
    fun `open-meteo observations-only refresh returns borrowed METAR readings`() = runTest {
        val service = DesktopWeatherService(
            latitude = 37.42,
            longitude = -122.08,
            weatherSource = WeatherSource.OPEN_METEO.id,
            injectedHttpClient = HttpClient(engine()),
        )
        try {
            val result = service.fetchObservationsOnly(recentOnly = true)

            assertTrue(
                "Open-Meteo borrows METAR, so an observations-only refresh must return its rows",
                result.rawObservations.isNotEmpty(),
            )
            val knuq = result.rawObservations.first { it.stationId == "KNUQ" }
            assertEquals(
                "Borrowed rows keep the provider's provenance; filing them under OPEN_METEO is what " +
                    "the observations primary key now exists to prevent",
                WeatherSource.METAR.id,
                knuq.api,
            )
        } finally {
            service.close()
        }
    }

    @Test
    fun `silurian borrows the same feed`() = runTest {
        val service = DesktopWeatherService(
            latitude = 37.42,
            longitude = -122.08,
            weatherSource = WeatherSource.SILURIAN.id,
            injectedHttpClient = HttpClient(engine()),
        )
        try {
            assertTrue(service.fetchObservationsOnly(recentOnly = true).rawObservations.isNotEmpty())
        } finally {
            service.close()
        }
    }

    /**
     * NWS ships its own observations, so it must keep the station-pull path — a borrowing detour
     * here would quietly regrade NWS against someone else's thermometers.
     *
     * Asserted by watching the wire rather than the return value: the NWS path needs a full
     * gridpoint fixture to get anywhere, and what matters is only that it never reaches
     * aviationweather.gov.
     */
    @Test
    fun `NWS does not take the borrowed path`() = runTest {
        val hits = mutableListOf<String>()
        val recording = MockEngine { request ->
            hits += request.url.host + request.url.encodedPath
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val service = DesktopWeatherService(
            latitude = 37.42,
            longitude = -122.08,
            weatherSource = WeatherSource.NWS.id,
            injectedHttpClient = HttpClient(recording),
        )
        try {
            runCatching { service.fetchObservationsOnly(recentOnly = true) }
            assertTrue(
                "NWS must not fetch METAR; hit $hits",
                hits.none { it.contains("aviationweather") },
            )
        } finally {
            service.close()
        }
    }

    // Field names and types mirror the live feed: `id` (not icaoId) for stationinfo, and siteType
    // is an ARRAY. See AviationWeatherApiParseTest for the shapes these are checked against.
    private fun stationInfoJson() = """
        [
          {"id":"KNUQ","site":"Moffett Fed Airfld","lat":37.4161,"lon":-122.0492,"elev":11,"country":"US","siteType":["METAR"]},
          {"id":"KPAO","site":"Palo Alto Arpt","lat":37.4611,"lon":-122.1150,"elev":2,"country":"US","siteType":["METAR"]}
        ]
    """.trimIndent()

    private fun metarJson() = """
        [
          {"icaoId":"KNUQ","obsTime":1756008900,"temp":17.0,"dewp":15.0,"wdir":360,"wspd":5,
           "visib":"10+","lat":37.4161,"lon":-122.0492,
           "rawOb":"METAR KNUQ 241135Z AUTO 36005KT 10SM CLR 17/15 A2992 RMK AO2"}
        ]
    """.trimIndent()
}
