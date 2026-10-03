package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class NwsObservationBackfillerTest {

    private fun subject(source: NwsObservationSource) = NwsObservationBackfiller(
        observationSource = source,
        observationDao = mockk<ObservationDao>(relaxed = true),
        dailyHistoryDao = mockk<DailyHistoryDao>(relaxed = true),
        appLogDao = mockk<AppLogDao>(relaxed = true),
        dailyActualsStore = mockk<DailyActualsStore>(relaxed = true),
    )

    private fun station(id: String) = NwsApi.StationInfo(
        id = id,
        name = id,
        lat = 37.42,
        lon = -122.08,
        type = NwsApi.StationType.OFFICIAL,
    )

    private fun row(stationId: String) = ObservationEntity(
        stationId = stationId,
        stationName = stationId,
        timestamp = 1_791_000_000_000L,
        temperature = 62f,
        condition = "Clear",
        locationLat = 37.417,
        locationLon = -122.089,
        distanceKm = 3f,
        stationType = "OFFICIAL",
        fetchedAt = 1_791_000_000_000L,
        api = "NWS",
    )

    private fun sourceWithStations(
        stations: FetchOutcome<List<NwsApi.StationInfo>>,
        history: (String) -> HistoricalStationObservations = { HistoricalStationObservations(emptyList(), false) },
    ): NwsObservationSource {
        val source = mockk<NwsObservationSource>()
        coEvery { source.stationsForLocationOutcome(any(), any()) } returns stations
        coEvery {
            source.fetchHistorical(any(), any(), any(), any(), any(), any(), any(), any())
        } answers { history(firstArg<NwsApi.StationInfo>().id) }
        return source
    }

    @Test(expected = CancellationException::class)
    fun `daily backfill station discovery cancellation propagates`() = runTest {
        val source = mockk<NwsObservationSource>()
        coEvery { source.stationsForLocation(any(), any()) } throws CancellationException("stop")

        subject(source).backfillNwsObservationsIfNeeded(37.42, -122.08)
    }

    @Test(expected = CancellationException::class)
    fun `recent backfill station discovery cancellation propagates`() = runTest {
        val source = mockk<NwsObservationSource>()
        coEvery { source.stationsForLocationOutcome(any(), any()) } throws CancellationException("stop")

        subject(source).backfillRecentNwsObservations(37.42, -122.08, 24)
    }

    // emulator-5554 2026-10-03: /points timed out; this must read as "never reached NWS".
    @Test
    fun `station lookup failure is unreachable`() = runTest {
        val source = sourceWithStations(FetchOutcome.Failed("HttpRequestTimeoutException: timeout"))

        val result = subject(source).backfillRecentNwsObservations(37.42, -122.08, 72)

        assertTrue(result.unreachable)
        assertEquals(0, result.stationsTried)
    }

    @Test
    fun `answered empty station list is not unreachable`() = runTest {
        val source = sourceWithStations(FetchOutcome.NoData)

        assertFalse(subject(source).backfillRecentNwsObservations(37.42, -122.08, 72).unreachable)
    }

    @Test
    fun `every station fetch failing is unreachable`() = runTest {
        val source = sourceWithStations(FetchOutcome.Success(listOf(station("KNUQ"), station("KSJC")))) {
            HistoricalStationObservations(emptyList(), usedWebFallback = false, apiFailure = "IOException")
        }

        assertTrue(subject(source).backfillRecentNwsObservations(37.42, -122.08, 72).unreachable)
    }

    @Test
    fun `one station answering is not unreachable`() = runTest {
        val source = sourceWithStations(FetchOutcome.Success(listOf(station("KNUQ"), station("KSJC")))) { id ->
            if (id == "KNUQ") {
                HistoricalStationObservations(emptyList(), usedWebFallback = false, apiFailure = "IOException")
            } else {
                HistoricalStationObservations(listOf(row(id)), usedWebFallback = false)
            }
        }

        val result = subject(source).backfillRecentNwsObservations(37.42, -122.08, 72)

        assertFalse(result.unreachable)
        assertEquals(1, result.rowsFetched)
    }

    @Test
    fun `stations answering with no rows is not unreachable`() = runTest {
        val source = sourceWithStations(FetchOutcome.Success(listOf(station("KNUQ"))))

        assertFalse(subject(source).backfillRecentNwsObservations(37.42, -122.08, 72).unreachable)
    }
}
