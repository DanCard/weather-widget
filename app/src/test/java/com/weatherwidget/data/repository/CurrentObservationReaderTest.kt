package com.weatherwidget.data.repository

import com.weatherwidget.data.local.ObservationDao
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

        val result = latestUsableNwsObservationsByStation(listOf(rejected, clean))

        assertEquals(listOf(clean), result)
    }

    @Test
    fun `all QC failed station contributes nothing and output is station sorted`() {
        val stationB = TestData.observation(stationId = "KZZZ", timestamp = 3_000L)
        val stationA = TestData.observation(stationId = "KAAA", timestamp = 2_000L)
        val rejected = TestData.observation(stationId = "KFAIL", timestamp = 4_000L).copy(qcFailed = true)

        val result = latestUsableNwsObservationsByStation(listOf(stationB, rejected, stationA))

        assertEquals(listOf("KAAA", "KZZZ"), result.map { it.stationId })
        assertTrue(result.none { it.qcFailed })
    }

    @Test
    fun `current blend and persisted actuals use only the nearest physical site`() = runTest {
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
            dao.getLatestNwsObservationsByStationAllTime(currentLat, currentLon, sinceMs)
        } returns listOf(otherNws, currentNws)

        val result = CurrentObservationReader(dao)
            .getMainObservationsWithComputedNwsBlend(currentLat, currentLon, sinceMs)

        assertEquals(listOf("OPEN_METEO_MAIN", "NWS_BLEND"), result.map { it.stationId })
        assertEquals(70f, result.first().temperature, 0f)
        assertEquals(72f, result.last().temperature, 0f)
    }
}
