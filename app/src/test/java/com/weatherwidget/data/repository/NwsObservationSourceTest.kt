package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.SharedPreferencesUtil
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class NwsObservationSourceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var nwsApi: NwsApi
    private lateinit var source: NwsObservationSource

    @Before
    fun setup() {
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit().clear().commit()
        nwsApi = mockk()
        source = NwsObservationSource(
            context,
            nwsApi,
            mockk<AppLogDao>(relaxed = true),
            synopticApi = null,
        )
    }

    @After
    fun tearDown() {
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit().clear().commit()
    }

    @Test(expected = CancellationException::class)
    fun `grid point cancellation propagates`() = runTest {
        coEvery { nwsApi.getGridPoint(any(), any()) } throws CancellationException("worker stopped")

        source.stationsForLocation(37.42, -122.08)
    }

    @Test(expected = CancellationException::class)
    fun `station list cancellation propagates`() = runTest {
        coEvery { nwsApi.getObservationStations(any()) } throws CancellationException("worker stopped")

        source.stationsFromUrl("https://example.test/stations")
    }

    @Test(expected = CancellationException::class)
    fun `historical observation cancellation propagates`() = runTest {
        val station = NwsApi.StationInfo(
            id = "KPAO",
            name = "Palo Alto",
            lat = 37.46,
            lon = -122.12,
            type = NwsApi.StationType.OFFICIAL,
        )
        coEvery { nwsApi.getObservations(any(), any(), any()) } throws
            CancellationException("worker stopped")

        source.fetchHistorical(
            stationInfo = station,
            stationIndex = 0,
            latitude = 37.42,
            longitude = -122.08,
            startTime = "2026-07-29T00:00:00Z",
            endTime = "2026-07-30T00:00:00Z",
            webWindowMinutes = 24 * 60L,
            fallbackLogTag = "TEST_FALLBACK",
        )
    }

    @Test
    fun `expired cached stations remain usable when refresh fails`() = runTest {
        val url = "https://example.test/stations"
        val station = NwsApi.StationInfo(
            id = "KPAO",
            name = "Palo Alto",
            lat = 37.46,
            lon = -122.12,
            type = NwsApi.StationType.OFFICIAL,
        )
        SharedPreferencesUtil.getPrefs(context, "weather_prefs")
            .edit()
            .putString(
                "observation_stations_v4_${url.hashCode()}",
                NwsApi.encodeStationInfo(station),
            )
            .putLong("observation_stations_time_v4_${url.hashCode()}", 0L)
            .commit()
        coEvery { nwsApi.getObservationStations(url) } throws IOException("offline")

        assertEquals(listOf(station), source.stationsFromUrl(url))
    }
}
