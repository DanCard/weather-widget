package com.weatherwidget.desktop

import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.SynopticApi
import com.weatherwidget.shared.observations.NwsBlend
import com.weatherwidget.test.category.MediumDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Every NWS station past the IDW's 3h decay: the blend has nothing to weight, so there must be no
 * `NWS_BLEND` row. Desktop's observations-only path used to write one anyway, carrying the nearest
 * station's stale reading as "the blend" (Android never did). See plans/261002-share-nws-blend.md.
 */
@Category(MediumDuration::class)
class DesktopNwsBlendStaleTest {

    @Test
    fun `stale-only stations produce no NWS_BLEND row`() = runTest {
        val result = fetchWithReadingAgedHours(5)

        assertTrue("the stale station row itself is still stored", result.rawObservations.any { it.stationId == "KNUQ" })
        assertTrue(
            "a stale-only blend must not be stored as an observation; rows=${result.rawObservations.map { it.stationId }}",
            result.rawObservations.none { it.stationId == NwsBlend.STATION_ID },
        )
    }

    /**
     * Desktop no longer stores the blend at all — the repository computes it on read, like Android
     * (plans/261009-desktop-stops-storing-nws-blend.md). A fresh station still feeds the header.
     */
    @Test
    fun `fresh stations feed the header but store no NWS_BLEND row`() = runTest {
        val result = fetchWithReadingAgedHours(0)

        assertNotNull("the blend still supplies the header temperature", result.providerCurrentTemp)
        assertTrue(result.rawObservations.any { it.stationId == "KNUQ" })
        assertTrue(
            "the blend must not be stored; rows=${result.rawObservations.map { it.stationId }}",
            result.rawObservations.none { it.stationId == NwsBlend.STATION_ID },
        )
    }

    private suspend fun fetchWithReadingAgedHours(hours: Long) = run {
        val nwsApi = mockk<NwsApi>()
        val station = NwsApi.StationInfo("KNUQ", "Moffett Field", 37.4058, -122.0480, StationType.OFFICIAL)
        coEvery { nwsApi.getGridPoint(any(), any()) } returns
            NwsApi.GridPointInfo("MTR", 80, 80, "http://dummy/forecast", "http://dummy/stations")
        coEvery { nwsApi.getObservationStations(any()) } returns listOf(station)
        val reading = NwsApi.Observation(
            timestamp = ZonedDateTime.now().minusHours(hours).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            temperatureCelsius = 20.0f,
            textDescription = "Clear",
            stationName = "Moffett Field",
        )
        coEvery { nwsApi.getObservations(any(), any(), any()) } returns listOf(reading)
        coEvery { nwsApi.getLatestObservationDetailedResult(any(), any()) } returns FetchOutcome.Success(reading)

        val httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
        val service = DesktopWeatherService(
            37.4220, -122.0841, "NWS",
            injectedHttpClient = httpClient,
            injectedNwsApi = nwsApi,
            // Blank token: Synoptic is not configured, so KNUQ is the only station.
            injectedSynopticApi = SynopticApi(httpClient, Json) { "" },
        )

        service.fetchObservationsOnly(recentOnly = false)
    }
}
