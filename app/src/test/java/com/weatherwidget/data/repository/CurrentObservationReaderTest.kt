package com.weatherwidget.data.repository

import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.toReading
import com.weatherwidget.shared.observations.NwsBlend
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.testutil.TestData
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class CurrentObservationReaderTest {

    @Test
    fun `newer QC failed row does not hide stations newest usable row`() {
        val clean = TestData.observation(stationId = "KPAO", timestamp = 1_000L, temperature = 72f)
        val rejected = clean.copy(timestamp = 2_000L, temperature = 50f, qcFailed = true)

        val result = NwsBlend.latestUsableByStation(listOf(rejected, clean).map { it.toReading() })

        assertEquals(listOf(clean.toReading()), result)
    }

    @Test
    fun `all QC failed station contributes nothing and output is station sorted`() {
        val stationB = TestData.observation(stationId = "KZZZ", timestamp = 3_000L)
        val stationA = TestData.observation(stationId = "KAAA", timestamp = 2_000L)
        val rejected = TestData.observation(stationId = "KFAIL", timestamp = 4_000L).copy(qcFailed = true)

        val result = NwsBlend.latestUsableByStation(listOf(stationB, rejected, stationA).map { it.toReading() })

        assertEquals(listOf("KAAA", "KZZZ"), result.map { it.stationId })
        assertTrue(result.none { it.qcFailed })
    }

    @Test
    fun `persisted actuals use the nearest site and the blend keeps the nearer duplicate`() = runTest {
        val dao = mockk<ObservationDao>()
        val currentLat = 37.420
        val currentLon = -122.080
        val otherLat = 37.425
        val otherLon = -122.075
        val nowMs = System.currentTimeMillis()
        val sinceMs = nowMs - 60_000L
        val currentMain = TestData.observation(
            stationId = "OPEN_METEO_MAIN",
            timestamp = nowMs,
            temperature = 70f,
            api = "OPEN_METEO",
        ).copy(locationLat = currentLat, locationLon = currentLon)
        val otherMain = currentMain.copy(
            temperature = 20f,
            locationLat = otherLat,
            locationLon = otherLon,
        )
        val currentNws = TestData.observation(
            stationId = "KPAO",
            timestamp = nowMs,
            temperature = 72f,
            distanceKm = 1f,
            api = "NWS",
        ).copy(locationLat = currentLat, locationLon = currentLon)
        val otherNws = currentNws.copy(
            temperature = 22f,
            locationLat = otherLat,
            locationLon = otherLon,
        )
        coEvery {
            dao.getLatestMainObservationsExcludingNws(currentLat, currentLon, sinceMs)
        } returns listOf(otherMain, currentMain)
        coEvery {
            dao.getLatestNwsObservationCandidatesByStationAllTime(currentLat, currentLon, any())
        } returns listOf(otherNws, currentNws)

        val result = CurrentObservationReader(dao)
            .getMainObservationsWithComputedNwsBlend(currentLat, currentLon, sinceMs, nowMs)

        assertEquals(listOf("OPEN_METEO_MAIN", "NWS_BLEND"), result.map { it.stationId })
        assertEquals(70f, result.first().temperature, 0f)
        assertEquals(72f, result.last().temperature, 0f)
    }

    private fun nws(stationId: String, timestamp: Long, temperature: Float, lat: Double = 37.417, lon: Double = -122.089) =
        TestData.observation(stationId = stationId, timestamp = timestamp, temperature = temperature, distanceKm = 3f, api = "NWS")
            .copy(locationLat = lat, locationLon = lon, fetchedAt = timestamp)

    private fun readerWith(nwsRows: List<com.weatherwidget.data.local.ObservationEntity>) =
        CurrentObservationReader(
            mockk<ObservationDao>().also { dao ->
                coEvery { dao.getLatestMainObservationsExcludingNws(any(), any(), any()) } returns emptyList()
                coEvery { dao.getLatestNwsObservationCandidatesByStationAllTime(any(), any(), any()) } returns nwsRows
            },
        )

    /**
     * Callers pass local midnight as sinceMs. At 00:15 a 23:30 reading is 45 minutes old and the IDW
     * still weights it; the blend used to drop it because it predated midnight, while desktop kept it.
     */
    @Test
    fun `just after midnight the blend still uses a reading from before midnight`() = runTest {
        val midnight = 1_791_615_600_000L
        val now = midnight + 15 * 60_000L
        val result = readerWith(listOf(nws("KNUQ", midnight - 30 * 60_000L, 58f)))
            .getMainObservationsWithComputedNwsBlend(37.417, -122.089, sinceMs = midnight, nowMs = now)

        assertEquals(listOf(NwsBlend.STATION_ID), result.map { it.stationId })
        assertEquals(58f, result.single().temperature, 0.01f)
    }

    /**
     * Fold 2026-08-27 class: back from an ~800 m walk, the newest KNUQ reading sits under the walk's
     * fetch site. Collapsing to the nearest site dropped it and blended a reading an hour older.
     */
    @Test
    fun `the blend merges nearby fetch sites instead of dropping the newest reading`() = runTest {
        val now = 1_791_600_000_000L
        val home = nws("KNUQ", now - 70 * 60_000L, 60f)
        val walk = nws("KNUQ", now - 10 * 60_000L, 66f, lat = 37.424, lon = -122.089)
        val result = readerWith(listOf(home, walk))
            .getMainObservationsWithComputedNwsBlend(37.417, -122.089, sinceMs = now - 86_400_000L, nowMs = now)

        assertEquals(66f, result.single().temperature, 0.01f)
        assertEquals(walk.timestamp, result.single().timestamp)
    }
}
