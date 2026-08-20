package com.weatherwidget.desktop

import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.SynopticApi
import com.weatherwidget.test.category.MediumDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.junit.experimental.categories.Category

/**
 * Pins the latest-only observation path (plans/260820-desktop-observation-loop-latest-only.md):
 * the 10-minute current-temperature cycle must fetch only each station's newest reading, not the
 * ~500-row 7-day history window, which the full forecast pull already refreshes.
 */
@Category(MediumDuration::class)
class DesktopObservationLatestOnlyTest {

    private val lat = 37.4220
    private val lon = -122.0841

    private fun station(id: String, name: String, type: NwsApi.StationType) =
        NwsApi.StationInfo(id, name, lat, lon, type)

    private fun freshObservation(stationId: String, minutesAgo: Long) = NwsApi.Observation(
        timestamp = ZonedDateTime.now().minusMinutes(minutesAgo).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        temperatureCelsius = 20.0f,
        textDescription = "Clear",
        stationName = stationId,
    )

    private fun serviceWith(
        nwsApi: NwsApi,
        synopticApi: SynopticApi = mockk(),
    ) = DesktopWeatherService(
        lat, lon, "NWS",
        injectedNwsApi = nwsApi,
        injectedSynopticApi = synopticApi,
    )

    @Test
    fun `latestOnly fetches only the newest reading and never the history window`() = runTest {
        val nws = mockk<NwsApi>()
        val synoptic = mockk<SynopticApi>()
        val station = station("KNUQ", "Moffett Field", NwsApi.StationType.OFFICIAL)
        val grid = NwsApi.GridPointInfo("MTR", 80, 80, "http://dummy/forecast", "http://dummy/stations")

        coEvery { nws.getGridPoint(any(), any()) } returns grid
        coEvery { nws.getObservationStations(any()) } returns listOf(station)
        coEvery { nws.getLatestObservationDetailedResult(any(), any()) } returns
            FetchOutcome.Success(freshObservation("KNUQ", 5))
        // Synoptic fetch-first policy (nearest 3 stations): stub it to a definitive empty answer so
        // the web fallback neither contributes nor fails the cycle.
        coEvery { synoptic.fetchSynopticObservations(any(), any(), any()) } returns FetchOutcome.NoData

        val result = serviceWith(nws, synoptic).fetchObservationsOnly(latestOnly = true)

        // Exactly 2 rows: the single station's newest reading + the synthetic NWS_BLEND blend row.
        // Nothing else — the 7-day history window is skipped, and the Synoptic fallback is stubbed
        // to NoData so no web reading leaks in. This is the "few rows processed" invariant, not a
        // proxy: a regression that fetched history through a different path would fail here even if
        // the getObservations coVerify below somehow stayed green.
        assertEquals(2, result.rawObservations.size)
        assertEquals(setOf("KNUQ", "NWS_BLEND"), result.rawObservations.map { it.stationId }.toSet())
        // Current temp resolved from the single latest reading.
        assertEquals((20.0f * 1.8f) + 32f, result.providerCurrentTemp!!, 0.01f)

        // The history window is never requested in latest-only mode.
        coVerify(exactly = 0) { nws.getObservations(any(), any(), any()) }
        coVerify(exactly = 1) { nws.getLatestObservationDetailedResult(any(), any()) }
    }

    @Test
    fun `full fetch still requests the history window`() = runTest {
        val nws = mockk<NwsApi>()
        val synoptic = mockk<SynopticApi>()
        val station = station("KNUQ", "Moffett Field", NwsApi.StationType.OFFICIAL)
        val grid = NwsApi.GridPointInfo("MTR", 80, 80, "http://dummy/forecast", "http://dummy/stations")

        coEvery { nws.getGridPoint(any(), any()) } returns grid
        coEvery { nws.getObservationStations(any()) } returns listOf(station)
        coEvery { nws.getObservations(any(), any(), any()) } returns listOf(freshObservation("KNUQ", 60))
        coEvery { nws.getLatestObservationDetailedResult(any(), any()) } returns
            FetchOutcome.Success(freshObservation("KNUQ", 5))
        coEvery { synoptic.fetchSynopticObservations(any(), any(), any()) } returns FetchOutcome.NoData

        serviceWith(nws, synoptic).fetchObservationsOnly(latestOnly = false)

        coVerify(exactly = 1) { nws.getObservations(any(), any(), any()) }
    }
}
