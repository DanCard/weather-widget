package com.weatherwidget.desktop

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import io.mockk.coEvery
import io.mockk.mockk
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

    private fun engine(requestedHours: MutableList<Int> = mutableListOf()) = MockEngine { request ->
        val path = request.url.encodedPath
        if (path.endsWith("/metar")) {
            request.url.parameters["hours"]?.toIntOrNull()?.let(requestedHours::add)
        }
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
        val requestedHours = mutableListOf<Int>()
        val service = DesktopWeatherService(
            latitude = 37.42,
            longitude = -122.08,
            weatherSource = WeatherSource.OPEN_METEO.id,
            injectedHttpClient = HttpClient(engine(requestedHours)),
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
            assertEquals(listOf(DesktopWeatherService.RECENT_BORROWED_METAR_HOURS), requestedHours)
        } finally {
            service.close()
        }
    }

    @Test
    fun `full refresh mode requests bounded borrowed METAR recovery history`() = runTest {
        val requestedHours = mutableListOf<Int>()
        val service = DesktopWeatherService(
            latitude = 37.42,
            longitude = -122.08,
            weatherSource = WeatherSource.OPEN_METEO.id,
            injectedHttpClient = HttpClient(engine(requestedHours)),
        )
        try {
            val result = service.fetchObservationsOnly(recentOnly = false)

            assertTrue(result.rawObservations.isNotEmpty())
            assertEquals(listOf(DesktopWeatherService.RECOVERY_BORROWED_METAR_HOURS), requestedHours)
            assertTrue(result.rawObservations.all { it.api == WeatherSource.METAR.id })
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

    @Test
    fun `NWS runs Aviation Weather in parallel and uses its newer matching METAR`() = runTest {
        val hits = mutableListOf<String>()
        val nowSeconds = System.currentTimeMillis() / 1000L
        val recording = MockEngine { request ->
            hits += request.url.host + request.url.encodedPath
            val content = when {
                request.url.encodedPath.endsWith("/stationinfo") -> stationInfoJson()
                request.url.encodedPath.endsWith("/metar") -> """
                    [
                      {"icaoId":"KNUQ","obsTime":$nowSeconds,"temp":19.0,"dewp":15.0,
                       "wdir":360,"wspd":5,"visib":"10+","lat":37.4161,"lon":-122.0492,
                       "rawOb":"METAR KNUQ FRESH AUTO 36005KT 10SM CLR 19/15 A2992 RMK AO2"}
                    ]
                """.trimIndent()
                else -> "[]"
            }
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val nws = mockk<NwsApi>()
        val station = NwsApi.StationInfo(
            "KNUQ", "Moffett Field", 37.4161, -122.0492, NwsApi.StationType.OFFICIAL,
        )
        val grid = NwsApi.GridPointInfo("MTR", 80, 80, "http://dummy/forecast", "http://dummy/stations")
        val old = NwsApi.Observation(
            timestamp = java.time.Instant.ofEpochSecond(nowSeconds - 2 * 3600).toString(),
            temperatureCelsius = 17f,
            textDescription = "Cloudy",
        )
        coEvery { nws.getGridPoint(any(), any()) } returns grid
        coEvery { nws.getObservationStations(any()) } returns listOf(station)
        coEvery { nws.getObservations(any(), any(), any()) } returns listOf(old)
        coEvery { nws.getLatestObservationDetailedResult(any(), any()) } returns FetchOutcome.Success(old)
        val service = DesktopWeatherService(
            latitude = 37.42,
            longitude = -122.08,
            weatherSource = WeatherSource.NWS.id,
            injectedHttpClient = HttpClient(recording),
            injectedNwsApi = nws,
        )
        try {
            val result = service.fetchObservationsOnly(recentOnly = true)
            val knuq = result.rawObservations.first { it.stationId == "KNUQ" && it.api == "NWS" }
            assertEquals(nowSeconds * 1000L, knuq.timestamp)
            assertTrue(knuq.isWebFallback)
            assertEquals((19f * 1.8f) + 32f, result.providerCurrentTemp!!, 0.01f)
            assertTrue(
                "NWS fetch-both must reach Aviation Weather; hit $hits",
                hits.any { it.contains("aviationweather") && it.endsWith("/metar") },
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
