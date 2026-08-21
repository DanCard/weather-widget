package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.SynopticApi
import com.weatherwidget.shared.observations.MetarRawSkyParser
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.SharedPreferencesUtil
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * The emulator failure of 2026-08-21, reproduced end to end across [NwsObservationSource],
     * [ObservationFallbackPolicy], [MetarRawSkyParser] and [NwsObservationMapper].
     *
     * KNUQ's 72h window timed out after 30 s. The catch flattened that to an empty list, which the
     * fallback reason rendered as "empty" — reading as "this station has no history" for a station
     * that had 196 observations. The Synoptic fallback that covered for it then dropped the sky
     * condition it was already receiving, so the nearest official station (3.8 km) contributed no
     * cloud at all and the curve ran off KSJC 15.9 km away.
     *
     * Both halves are asserted here: the reason must name the failure, and the stored rows must
     * carry cloud.
     */
    @Test
    fun `a timed-out window falls back to Synoptic with cloud intact and says why`() = runTest {
        val station = NwsApi.StationInfo(
            id = "KNUQ",
            name = "Moffett Federal Airfield",
            lat = 37.4161,
            lon = -122.0492,
            type = NwsApi.StationType.OFFICIAL,
        )
        val logDao = mockk<AppLogDao>(relaxed = true)
        val logged = mutableListOf<AppLogEntity>()
        coEvery { logDao.insert(capture(logged)) } returns Unit

        val synopticApi = mockk<SynopticApi>()
        // Real report bodies; the sky condition is parsed by the production parser, not hand-built.
        val raws = listOf(
            "KNUQ 211435Z AUTO 35003KT 10SM OVC012 16/13 A3005 RMK AO2",
            "KNUQ 211455Z AUTO 01005KT 10SM BKN013 16/13 A3005 RMK AO2",
        )
        coEvery { synopticApi.fetchSynopticObservations(any(), any(), any()) } returns
            FetchOutcome.Success(
                raws.mapIndexed { index, raw ->
                    NwsApi.Observation(
                        timestamp = "2026-08-21T14:${35 + index * 20}:00Z",
                        temperatureCelsius = 16f,
                        textDescription = "Overcast",
                        stationName = station.name,
                        cloudLayers = MetarRawSkyParser.layersFrom(raw),
                        isMetar = true,
                    )
                },
            )

        val sourceWithWeb = NwsObservationSource(context, nwsApi, logDao, synopticApi = synopticApi)
        coEvery { nwsApi.getObservations(any(), any(), any()) } throws
            IOException("Request timeout has expired")

        val result = sourceWithWeb.fetchHistorical(
            stationInfo = station,
            stationIndex = 0,
            latitude = 37.417,
            longitude = -122.089,
            startTime = "2026-08-18T17:00:09Z",
            endTime = "2026-08-21T17:00:09Z",
            webWindowMinutes = 72 * 60L,
            fallbackLogTag = "OBS_HOURLY_SYNOPTIC_FALLBACK",
        )

        // The failure is named, not laundered into "this station is empty".
        val fallbackLine = logged.single { it.tag == "OBS_HOURLY_SYNOPTIC_FALLBACK" }.message
        assertTrue("reason must name the failure, was: $fallbackLine", "fetch_failed" in fallbackLine)
        assertFalse("a timeout is not an empty station", "reason=empty" in fallbackLine)

        // And the rows that covered for it carry sky condition all the way to the entity.
        assertEquals(2, result.entities.size)
        assertTrue(result.usedWebFallback)
        assertEquals("IOException", result.apiFailure)
        assertEquals(listOf(100, 75), result.entities.map { it.cloudCoverLow })
        assertTrue(result.entities.all { it.isWebFallback })
        assertTrue(result.entities.all { it.isMetar })
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
