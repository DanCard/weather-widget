package com.weatherwidget.desktop

import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.SynopticApi
import com.weatherwidget.test.category.MediumDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.junit.experimental.categories.Category

/**
 * Pins the recent-window observation path
 * (plans/260820-observation-loop-recent-window-not-latest-row.md): the current-temperature cycle
 * fetches a short window — not the ~500-row 7-day history the full forecast pull already refreshes,
 * and not a single latest row either, which discarded the readings of stations publishing faster
 * than the poll interval.
 */
@Category(MediumDuration::class)
class DesktopObservationRecentWindowTest {

    private val lat = 37.4220
    private val lon = -122.0841

    private fun station(id: String, name: String, type: NwsApi.StationType) =
        NwsApi.StationInfo(id, name, lat, lon, type)

    private fun freshObservation(stationId: String, minutesAgo: Long, tempC: Float = 20.0f) = NwsApi.Observation(
        timestamp = ZonedDateTime.now().minusMinutes(minutesAgo).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        temperatureCelsius = tempC,
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
    fun `recentOnly keeps every reading in the window, not just the newest`() = runTest {
        val nws = mockk<NwsApi>()
        val synoptic = mockk<SynopticApi>()
        val station = station("KSJC", "San Jose", NwsApi.StationType.OFFICIAL)
        val grid = NwsApi.GridPointInfo("MTR", 80, 80, "http://dummy/forecast", "http://dummy/stations")

        // A KSJC-shaped station: publishing every 5 minutes, so a 10-minute poll straddles several
        // readings. This is the exact shape that lost the 2026-08-20 10:30 observation.
        val window = listOf(
            freshObservation("KSJC", 20, 18.0f),
            freshObservation("KSJC", 15, 18.5f),
            freshObservation("KSJC", 10, 19.0f),
            freshObservation("KSJC", 5, 19.5f),
        )

        coEvery { nws.getGridPoint(any(), any()) } returns grid
        coEvery { nws.getObservationStations(any()) } returns listOf(station)
        coEvery { nws.getObservations(any(), any(), any()) } returns window
        coEvery { nws.getLatestObservationDetailedResult(any(), any()) } returns
            FetchOutcome.Success(window.last())
        // Synoptic fetch-first policy (nearest 3 stations): stub it to a definitive empty answer so
        // the web fallback neither contributes nor fails the cycle.
        coEvery { synoptic.fetchSynopticObservations(any(), any(), any()) } returns FetchOutcome.NoData

        val result = serviceWith(nws, synoptic).fetchObservationsOnly(recentOnly = true)

        // All 4 window readings survive to the stored rows. The old latest-only path yielded just
        // one KSJC timestamp here, silently discarding the three older readings — that is the
        // dropped 2026-08-20 10:30 observation in miniature.
        //
        // Asserted on DISTINCT timestamps, not row count: the assembly emits `latest` alongside the
        // window, so the newest reading legitimately appears twice and the (stationId, timestamp)
        // primary key collapses it on write. Timestamps are what actually reach the DB.
        val ksjcTimestamps = result.rawObservations.filter { it.stationId == "KSJC" }.map { it.timestamp }
        assertEquals(4, ksjcTimestamps.distinct().size)
        assertEquals(setOf("KSJC", "NWS_BLEND"), result.rawObservations.map { it.stationId }.toSet())

        // Current temp still anchors on the newest reading, not an older one from the window.
        assertEquals((19.5f * 1.8f) + 32f, result.providerCurrentTemp!!, 0.01f)
    }

    @Test
    fun `recentOnly requests a short window, not the 7-day history`() = runTest {
        val nws = mockk<NwsApi>()
        val synoptic = mockk<SynopticApi>()
        val station = station("KNUQ", "Moffett Field", NwsApi.StationType.OFFICIAL)
        val grid = NwsApi.GridPointInfo("MTR", 80, 80, "http://dummy/forecast", "http://dummy/stations")

        coEvery { nws.getGridPoint(any(), any()) } returns grid
        coEvery { nws.getObservationStations(any()) } returns listOf(station)
        coEvery { nws.getObservations(any(), any(), any()) } returns listOf(freshObservation("KNUQ", 5))
        coEvery { nws.getLatestObservationDetailedResult(any(), any()) } returns
            FetchOutcome.Success(freshObservation("KNUQ", 5))
        coEvery { synoptic.fetchSynopticObservations(any(), any(), any()) } returns FetchOutcome.NoData

        val start = slot<String>()
        val end = slot<String>()
        coEvery { nws.getObservations(any(), capture(start), capture(end)) } returns
            listOf(freshObservation("KNUQ", 5))

        serviceWith(nws, synoptic).fetchObservationsOnly(recentOnly = true)

        // Exactly one windowed call per station — the CPU win of 2befc157 was dropping the 7-day
        // pull, and that must not creep back in.
        coVerify(exactly = 1) { nws.getObservations(any(), any(), any()) }

        val spanMinutes = Duration.between(
            ZonedDateTime.parse(start.captured).toInstant(),
            ZonedDateTime.parse(end.captured).toInstant(),
        ).toMinutes()
        assertEquals(DesktopWeatherService.RECENT_OBSERVATION_WINDOW_MINUTES, spanMinutes)
        // Guard the intent, not just the constant: a 7-day window would be 10080 minutes.
        assertTrue("recent window must stay far below the 7-day pull", spanMinutes < 24 * 60)
    }

    @Test
    fun `recentOnly still contributes the latest reading when the window is empty`() = runTest {
        val nws = mockk<NwsApi>()
        val synoptic = mockk<SynopticApi>()
        val station = station("KPAO", "Palo Alto", NwsApi.StationType.OFFICIAL)
        val grid = NwsApi.GridPointInfo("MTR", 80, 80, "http://dummy/forecast", "http://dummy/stations")

        coEvery { nws.getGridPoint(any(), any()) } returns grid
        coEvery { nws.getObservationStations(any()) } returns listOf(station)
        // A slow station (KPAO reports ~every 90 min): the short window can legitimately come back
        // empty while the latest lookup still holds a usable reading. Dropping the station for the
        // cycle would starve the current-temp blend.
        coEvery { nws.getObservations(any(), any(), any()) } returns emptyList()
        coEvery { nws.getLatestObservationDetailedResult(any(), any()) } returns
            FetchOutcome.Success(freshObservation("KPAO", 5, 21.0f))
        coEvery { synoptic.fetchSynopticObservations(any(), any(), any()) } returns FetchOutcome.NoData

        val result = serviceWith(nws, synoptic).fetchObservationsOnly(recentOnly = true)

        assertEquals(setOf("KPAO", "NWS_BLEND"), result.rawObservations.map { it.stationId }.toSet())
        assertEquals((21.0f * 1.8f) + 32f, result.providerCurrentTemp!!, 0.01f)
    }

    @Test
    fun `full fetch still requests the multi-day history window`() = runTest {
        val nws = mockk<NwsApi>()
        val synoptic = mockk<SynopticApi>()
        val station = station("KNUQ", "Moffett Field", NwsApi.StationType.OFFICIAL)
        val grid = NwsApi.GridPointInfo("MTR", 80, 80, "http://dummy/forecast", "http://dummy/stations")

        coEvery { nws.getGridPoint(any(), any()) } returns grid
        coEvery { nws.getObservationStations(any()) } returns listOf(station)
        coEvery { nws.getLatestObservationDetailedResult(any(), any()) } returns
            FetchOutcome.Success(freshObservation("KNUQ", 5))
        coEvery { synoptic.fetchSynopticObservations(any(), any(), any()) } returns FetchOutcome.NoData

        val start = slot<String>()
        val end = slot<String>()
        coEvery { nws.getObservations(any(), capture(start), capture(end)) } returns
            listOf(freshObservation("KNUQ", 60))

        serviceWith(nws, synoptic).fetchObservationsOnly(recentOnly = false)

        coVerify(exactly = 1) { nws.getObservations(any(), any(), any()) }
        val spanDays = Duration.between(
            ZonedDateTime.parse(start.captured).toInstant(),
            ZonedDateTime.parse(end.captured).toInstant(),
        ).toDays()
        assertEquals(DesktopWeatherService.HISTORY_DAYS, spanDays)
    }
}
