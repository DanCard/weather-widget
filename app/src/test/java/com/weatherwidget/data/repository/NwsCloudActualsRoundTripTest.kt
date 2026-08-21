package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Integration round-trip for the NWS cloud actual: `NwsObservationSource.toEntity` writes the
 * METAR-derived low-layer percent onto real-station rows, Room stores it, and
 * `ObservationDao.getCloudActuals` blends those rows — while a PWS row (no ceilometer, empty layer
 * list) and a foreign-source synthetic row contribute nothing. See
 * plans/260820-nws-metar-cloud-cover-idw-blend.md §6.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class NwsCloudActualsRoundTripTest {

    private lateinit var db: WeatherDatabase
    private lateinit var source: NwsObservationSource

    private val userLat = 37.42
    private val userLon = -122.08

    @Before
    fun setup() {
        db = TestDatabase.create()
        source = NwsObservationSource(
            RuntimeEnvironment.getApplication(),
            mockk(),
            mockk(relaxed = true),
            synopticApi = null,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun station(id: String, lat: Double, lon: Double, official: Boolean) = NwsApi.StationInfo(
        id = id,
        name = id,
        lat = lat,
        lon = lon,
        type = if (official) NwsApi.StationType.OFFICIAL else NwsApi.StationType.PERSONAL,
    )

    private fun observation(
        timestamp: String,
        layers: List<NwsApi.CloudLayer>,
        tempC: Double = 20.0,
        isMetar: Boolean = false,
    ) = NwsApi.Observation(
        timestamp = timestamp,
        temperatureCelsius = tempC.toFloat(),
        textDescription = "Clear",
        cloudLayers = layers,
        isMetar = isMetar,
    )

    @Test
    fun `pws and foreign-source rows contribute nothing and the blend width matches reporters`() = runBlocking {
        val dao = db.observationDao()
        // 21:53 and 21:55 both round into the 22:00 bucket; every station is at the same fetch site.
        val entities = listOf(
            // KNUQ (~3.8 km): OVC at 300 m -> low 100.
            source.toEntity(
                observation("2026-08-20T21:53:00+00:00", listOf(NwsApi.CloudLayer("OVC", 300.0))),
                station("KNUQ", 37.41, -122.05, official = true),
                userLat, userLon,
            ),
            // KPAO (~6 km): BKN at 500 m -> low 75.
            source.toEntity(
                observation("2026-08-20T21:55:00+00:00", listOf(NwsApi.CloudLayer("BKN", 500.0))),
                station("KPAO", 37.46, -122.12, official = true),
                userLat, userLon,
            ),
            // AW020 (~2 km): a PWS — cloudLayers is always empty, so it stores no cloud and must
            // not narrow or pollute the blend, only the diagnostics.
            source.toEntity(
                observation("2026-08-20T21:53:00+00:00", emptyList()),
                station("AW020", 37.43, -122.07, official = false),
                userLat, userLon,
            ),
        )
        dao.insertAll(entities)

        // A foreign source's synthetic row at the same site and hour (cloud total 88) must not
        // join an NWS blend even though its distanceKm = 0 would win the near-zero snap.
        dao.insertAll(
            listOf(
                com.weatherwidget.data.local.ObservationEntity(
                    stationId = "OPEN_METEO_MAIN",
                    stationName = "Open-Meteo: History Backfill",
                    timestamp = 1_787_263_200_000L, // 2026-08-20T22:00:00Z
                    temperature = 68f,
                    condition = "Clear",
                    locationLat = entities.first().locationLat,
                    locationLon = entities.first().locationLon,
                    distanceKm = 0f,
                    stationType = "OFFICIAL",
                    api = WeatherSource.OPEN_METEO.id,
                    cloudCover = 88,
                ),
            ),
        )

        val result = dao.getCloudActuals(
            startTs = 1_787_259_600_000L, // 21:00Z
            endTs = 1_787_274_000_000L,   // 2026-08-21T00:00Z (exclusive)
            lat = userLat,
            lon = userLon,
            sourceId = WeatherSource.NWS.id,
        )

        assertTrue(result.isMetarBlend)
        // KNUQ (100, ~3.8 km) and KPAO (75, ~6 km): the IDW blend must land strictly between the
        // two station values — a hijacked 88 or 100/75 single-station value would fail this.
        val hourValue = result.hours[1_787_263_200_000L] // 22:00Z
        assertNotNull(hourValue)
        assertTrue("blend $hourValue not within (75, 100)", hourValue!! in 76..99)
        assertEquals(2, result.stats.blendWidthByHour[1_787_263_200_000L])
        assertEquals(2, result.stats.stationsWithLayers)
        assertEquals(1, result.stats.stationsSkipped)
    }

    @Test
    fun `write rule - metar sky condition lands on cloudCoverLow with a null total`() = runBlocking {
        val entity = source.toEntity(
            observation("2026-08-20T21:53:00+00:00", listOf(NwsApi.CloudLayer("BKN", 500.0))),
            station("KPAO", 37.46, -122.12, official = true),
            userLat, userLon,
        )
        // METAR is a below-~12,000 ft measurement; filing it as the total column would be the lie
        // (§3 of the plan). The low layer is the honest home for it.
        assertEquals(75, entity.cloudCoverLow)
        assertNull(entity.cloudCover)

        db.observationDao().insertAll(listOf(entity))
        val stored = db.observationDao().getObservationsInRange(
            entity.timestamp,
            entity.timestamp + 1,
            userLat,
            userLon,
        ).single()
        assertEquals(75, stored.cloudCoverLow)
        assertNull(stored.cloudCover)
    }

    @Test
    fun `a station set with no sky condition yields no actual hours`() = runBlocking {
        val dao = db.observationDao()
        dao.insertAll(
            listOf(
                source.toEntity(
                    observation("2026-08-20T21:53:00+00:00", emptyList()),
                    station("AW020", 37.43, -122.07, official = false),
                    userLat, userLon,
                ),
                source.toEntity(
                    observation("2026-08-20T21:53:00+00:00", emptyList()),
                    station("LOAC1", 37.35, -122.02, official = false),
                    userLat, userLon,
                ),
            ),
        )
        val result = dao.getCloudActuals(
            startTs = 1_787_259_600_000L,
            endTs = 1_787_274_000_000L,
            lat = userLat,
            lon = userLon,
            sourceId = WeatherSource.NWS.id,
        )
        assertTrue(result.hours.isEmpty())
        assertEquals(0, result.stats.stationsWithLayers)
        assertEquals(2, result.stats.stationsSkipped)
    }

    @Test
    fun `a report in the half-hour before the window still fills the first visible hour`() = runBlocking {
        val dao = db.observationDao()
        // The Samsung fold, 2026-08-21: the 1a-5a cloud graph drew its actual curve from 2a. KSJC
        // reported at 00:30, which ROUNDS INTO the 01:00 bucket — the window's first visible hour —
        // but the row read was `timestamp >= 01:00`, so the blend never saw it. Proving this
        // through Room, not just the blender, is the point: the blender always bucketed the report
        // correctly; it was the query range that dropped it.
        val windowStart = 1_787_263_200_000L // 22:00Z, hour-aligned
        val windowEnd = windowStart + 4 * 3_600_000L
        dao.insertAll(
            listOf(
                // 21:30Z: half an hour BEFORE the window, buckets into its first hour.
                source.toEntity(
                    observation("2026-08-20T21:30:00+00:00", listOf(NwsApi.CloudLayer("BKN", 500.0))),
                    station("KSJC", 37.36, -121.93, official = true),
                    userLat, userLon,
                ),
                // 23:05Z: comfortably inside the window, so a truncated read still returns this one
                // and the failure is specifically a missing FIRST hour, not an empty series.
                source.toEntity(
                    observation("2026-08-20T23:05:00+00:00", listOf(NwsApi.CloudLayer("OVC", 300.0))),
                    station("KSJC", 37.36, -121.93, official = true),
                    userLat, userLon,
                ),
            ),
        )

        val result = dao.getCloudActuals(
            startTs = windowStart,
            endTs = windowEnd,
            lat = userLat,
            lon = userLon,
            sourceId = WeatherSource.NWS.id,
        )

        assertEquals(75, result.hours[windowStart])
        assertEquals(100, result.hours[windowStart + 3_600_000L])
    }

    @Test
    fun `the METAR wins the hour over a 5-minute sample sitting on the mark`() = runBlocking {
        val dao = db.observationDao()
        // The KSJC shape, written through the real mapper and Room rather than hand-built readings:
        // the ASOS 5-minute row lands EXACTLY on the hour mark and the METAR sits 7 minutes before
        // it, so nearest-to-mark alone would hand the hour to the instantaneous sample every time.
        val hourMs = 1_787_263_200_000L // 2026-08-20T22:00:00Z, hour-aligned
        dao.insertAll(
            listOf(
                source.toEntity(
                    observation(
                        "2026-08-20T21:53:00+00:00",
                        listOf(NwsApi.CloudLayer("BKN", 500.0)),
                        isMetar = true,
                    ),
                    station("KSJC", 37.36, -121.93, official = true),
                    userLat, userLon,
                ),
                source.toEntity(
                    observation(
                        "2026-08-20T22:00:00+00:00",
                        // CLR at the ceilometer's detection ceiling: "nothing overhead right now",
                        // which is exactly the reading that must NOT win the hour.
                        listOf(NwsApi.CloudLayer("CLR", 3810.0)),
                    ),
                    station("KSJC", 37.36, -121.93, official = true),
                    userLat, userLon,
                ),
            ),
        )

        val result = dao.getCloudActuals(
            startTs = hourMs,
            endTs = hourMs + 3_600_000L,
            lat = userLat,
            lon = userLon,
            sourceId = WeatherSource.NWS.id,
        )

        // 75 (BKN, the METAR) — not 0 (CLR, the sample on the mark).
        assertEquals(75, result.hours[hourMs])
        assertEquals(1, result.stats.metarPreferredBuckets)
    }

    @Test
    fun `actual-capable non-NWS sources keep reading the synthetic backfill row`() = runBlocking {
        val dao = db.observationDao()
        val synthetic = com.weatherwidget.data.local.ObservationEntity(
            stationId = "WEATHER_API_MAIN",
            stationName = "WeatherAPI: History Backfill",
            timestamp = 1_787_263_200_000L,
            temperature = 68f,
            condition = "Clear",
            locationLat = com.weatherwidget.data.local.LocationMatch.quantize(userLat),
            locationLon = com.weatherwidget.data.local.LocationMatch.quantize(userLon),
            distanceKm = 0f,
            stationType = "OFFICIAL",
            api = WeatherSource.WEATHER_API.id,
            cloudCoverLow = 61,
        )
        dao.insertAll(listOf(synthetic))
        val result = dao.getCloudActuals(
            startTs = 1_787_259_600_000L,
            endTs = 1_787_274_000_000L,
            lat = userLat,
            lon = userLon,
            sourceId = WeatherSource.WEATHER_API.id,
        )
        assertEquals(mapOf(1_787_263_200_000L to 61), result.hours)
        assertTrue(!result.isMetarBlend)
    }
}
