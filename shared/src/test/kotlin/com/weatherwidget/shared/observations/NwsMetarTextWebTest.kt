package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.AviationWeatherApi
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.NwsMetarTextApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import com.weatherwidget.test.category.ShortDuration
import java.time.Instant
import java.util.Collections

/**
 * The NWS web leg end to end: [NwsMetarTextWeb] + [NwsMetarTextApi] parse + [MetarObservationMapper]
 * + [NwsStationLatestFetcher]'s prefer-newest merge. KNUQ, 2026-10-15: the API sat at 11:55 while
 * NWS's own text file had 12:35 (19:35Z); the stored row must be the 12:35 one, filed as NWS's.
 */
@Category(ShortDuration::class)
class NwsMetarTextWebTest {
    private val siteLat = 37.4166
    private val siteLon = -122.0889

    private fun station(id: String, type: StationType = StationType.OFFICIAL) =
        NwsApi.StationInfo(id = id, name = "$id name", lat = 37.4161, lon = -122.0492, type = type)

    private fun fixture(id: String) =
        checkNotNull(javaClass.getResourceAsStream("/nws_metar_text/$id.TXT")) { "missing fixture $id" }
            .bufferedReader().use { it.readText() }

    /** Serves the fixtures; a station with no fixture gets NoData (tgftp's 301 → 404). */
    private class FixtureApi(private val files: Map<String, String>) :
        NwsMetarTextApi(HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })) {
        val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun fetchLatest(stationId: String): FetchOutcome<AviationWeatherApi.MetarRow> {
            requested += stationId
            return files[stationId]?.let { parse(it, stationId) } ?: FetchOutcome.NoData
        }
    }

    private fun apiObservation(iso: String, tempC: Float) = NwsApi.Observation(
        timestamp = iso,
        temperatureCelsius = tempC,
        textDescription = "Clear",
        stationName = "KNUQ name",
    )

    @Test
    fun `the newer NWS text report wins over the lagging API and is stored as NWS's own`() = runBlocking {
        val api = FixtureApi(mapOf("KNUQ" to fixture("KNUQ")))
        val logs = mutableListOf<String>()
        val fetcher = NwsStationLatestFetcher(
            latestObservation = { FetchOutcome.Success(apiObservation("2026-10-15T18:55:00Z", 22f)) },
            log = { tag, message, _ -> logs += "$tag $message" },
            nowMs = { Instant.parse("2026-10-15T19:40:00Z").toEpochMilli() },
        )

        val result = coroutineScope {
            val web = NwsMetarTextWeb.start(this, api, listOf(station("KNUQ")), siteLat, siteLon)
            fetcher.fetch(station("KNUQ"), 0, siteLat, siteLon, web)
        }

        val chosen = result.chosen!!
        assertEquals(Instant.parse("2026-10-15T19:35:00Z").toEpochMilli(), chosen.timestamp)
        assertEquals(73.4f, chosen.temperature, 0.05f)
        assertEquals(WeatherSource.NWS.id, chosen.api)
        assertTrue(chosen.isWebFallback)
        assertTrue(chosen.isMetar)
        val line = logs.single { it.startsWith("OBS_WEB_API_DELTA") }
        assertTrue(line, "transport=nws_metar_text outcome=success" in line)
        assertTrue(line, "chosen=web" in line)
        assertTrue(line, Regex("webTempF=73\\.(4|39)").containsMatchIn(line))
        assertTrue(line, Regex("webMs=\\d+").containsMatchIn(line))
    }

    @Test
    fun `a station with no METAR file keeps its API reading`() = runBlocking {
        val api = FixtureApi(emptyMap())
        val fetcher = NwsStationLatestFetcher(
            latestObservation = { FetchOutcome.Success(apiObservation("2026-10-15T19:10:00Z", 24f)) },
            log = { _, _, _ -> },
        )
        val loac1 = station("LOAC1", StationType.RAWS)

        val result = coroutineScope {
            fetcher.fetch(loac1, 0, siteLat, siteLon, NwsMetarTextWeb.start(this, api, listOf(loac1), siteLat, siteLon))
        }

        assertEquals(false, result.chosen!!.isWebFallback)
        assertEquals(FetchOutcome.NoData, result.webOutcome)
        // Non-official stations (RAWS, PERSONAL) must never hit the network
        assertTrue("LOAC1 should not be requested: ${api.requested}", api.requested.isEmpty())
    }

    @Test
    fun `non-official stations are skipped while official stations are requested`() = runBlocking {
        val api = FixtureApi(emptyMap())
        val stations = listOf(
            station("KNUQ", StationType.OFFICIAL),
            station("AW020", StationType.PERSONAL),
            station("LOAC1", StationType.RAWS),
            station("KSJC", StationType.OFFICIAL),
        )

        coroutineScope {
            NwsMetarTextWeb.start(this, api, stations, siteLat, siteLon)
            kotlinx.coroutines.yield()
            assertEquals(setOf("KNUQ", "KSJC"), api.requested.toSet())
        }
    }

    @Test
    fun `web requests start for the five nearest stations before any API answer, and no further`() {
        runBlocking {
            val api = FixtureApi(emptyMap())
            val stations = (1..7).map { station("K00$it") }

            coroutineScope {
                NwsMetarTextWeb.start(this, api, stations, siteLat, siteLon)
                // Nothing has asked for a station's web rows yet; the requests are already out.
                kotlinx.coroutines.yield()
                assertEquals(
                    stations.take(ObservationFallbackPolicy.WEB_METRICS_STATIONS).map { it.id }.toSet(),
                    api.requested.toSet(),
                )
            }
        }
    }
}
