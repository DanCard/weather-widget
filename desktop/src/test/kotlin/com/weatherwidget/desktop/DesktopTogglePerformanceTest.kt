package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.data.model.StationType
import com.weatherwidget.shared.sourceview.SourceSwitchRunner
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import com.weatherwidget.shared.util.HeaderContent
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/**
 * Unit + real-SQLite integration tests for desktop API-toggle latency improvements
 * (performance/261118-desktop-api-toggle-latency.md).
 */
@Category(ShortDuration::class)
class DesktopTogglePerformanceTest {
    private val tempDirs = mutableListOf<Path>()

    @After
    fun tearDown() {
        DesktopSourceSwitch.install(dao = null)
        DesktopAppLogQueue.install(dao = null)
        tempDirs.forEach { dir ->
            dir.toFile().deleteRecursively()
        }
        tempDirs.clear()
    }

    private fun openTempDao(): DesktopWeatherDao {
        val dir = Files.createTempDirectory("desktop-toggle-perf-test")
        tempDirs.add(dir)
        val db = DesktopWeatherDatabase(dir.resolve("weather.db"))
        db.initialize()
        return DesktopWeatherDao(db)
    }

    @Test
    fun `DesktopSourceSwitch logs phase=data_ready then phase=rendered with renderMs`() {
        val dao = openTempDao()
        var clockMs = 1_000L
        DesktopAppLogQueue.install(dao)
        DesktopSourceSwitch.install(
            dao = dao,
            deferredScope = CoroutineScope(Dispatchers.Unconfined),
            elapsedMs = { clockMs },
        )

        val config = DesktopConfig(
            lat = 37.4166,
            lon = -122.0889,
            label = "Mountain View",
            settings = DesktopSettings(
                weatherSource = "NWS",
                visibleSources = listOf("NWS", "OPEN_METEO"),
            ),
        )

        DesktopSourceSwitch.switchTo(config, "OPEN_METEO", SourceViewTrigger.TOGGLE) { }

        // Premature draw before data_ready is ignored.
        clockMs = 1_050L
        DesktopSourceSwitch.onFrameRendered("OPEN_METEO")
        // Wrong source is ignored.
        DesktopSourceSwitch.onDataShown("NWS")

        // Data arrives at +40ms.
        clockMs = 1_040L
        DesktopSourceSwitch.onDataShown("OPEN_METEO")

        // First frame draws at +95ms (renderMs = 55ms).
        clockMs = 1_095L
        DesktopSourceSwitch.onFrameRendered("OPEN_METEO")

        // Subsequent frames are no-ops.
        clockMs = 1_500L
        DesktopSourceSwitch.onFrameRendered("OPEN_METEO")

        // Drain the async app-log writer queue.
        DesktopAppLogQueue.drain()

        val e2eLogs = dao.getRecentLogs(50)
            .filter { it.tag == SourceSwitchRunner.E2E_TAG }
            .map { it.message }
        println("SOURCE_SWITCH_E2E rows: $e2eLogs")
        assertTrue(
            "expected phase=data_ready sinceSwitchMs=40 in $e2eLogs",
            e2eLogs.any { it.contains("phase=data_ready") && it.contains("sinceSwitchMs=40") },
        )
        assertTrue(
            "expected phase=rendered sinceSwitchMs=95 renderMs=55 in $e2eLogs",
            e2eLogs.any { it.contains("phase=rendered") && it.contains("sinceSwitchMs=95") && it.contains("renderMs=55") },
        )
        assertEquals(
            "phase=rendered should fire exactly once per switch",
            1,
            e2eLogs.count { it.contains("phase=rendered") },
        )
    }

    @Test
    fun `DesktopSnapshotCache hits on repeat source toggle and invalidates on site or data change`() {
        val cache = DesktopSnapshotCache(maxAgeMs = 60_000L)
        val home = DesktopConfig(lat = 37.4166, lon = -122.0889, label = "Home")
        val nwsSnap = ForecastSnapshot("NWS", RawFetch(), ResolvedView())
        val omSnap = ForecastSnapshot("OPEN_METEO", RawFetch(), ResolvedView())

        val nwsKey = DesktopSnapshotCache.keyFor(home, "NWS")
        val omKey = DesktopSnapshotCache.keyFor(home, "OPEN_METEO")
        cache.put(nwsKey, nwsSnap, nowMs = 1_000L)
        cache.put(omKey, omSnap, nowMs = 1_500L)

        assertSame(nwsSnap, cache.get(nwsKey, nowMs = 2_000L))
        assertSame(omSnap, cache.get(omKey, nowMs = 2_000L))

        // Different actualsProvider override misses.
        val overriddenHome = home.copy(settings = home.settings.copy(actualsProviders = mapOf("OPEN_METEO" to "NWS")))
        assertNull(cache.get(DesktopSnapshotCache.keyFor(overriddenHome, "OPEN_METEO"), nowMs = 2_000L))

        // Expired entry misses.
        assertNull(cache.get(nwsKey, nowMs = 70_000L))

        // Moving to a different site evicts old-site entries.
        val away = home.copy(lat = 40.7128, lon = -74.0060, label = "NYC")
        cache.put(DesktopSnapshotCache.keyFor(away, "NWS"), nwsSnap, nowMs = 71_000L)
        assertEquals(1, cache.size())
        assertNull(cache.get(omKey, nowMs = 71_000L))

        // Daemon data update invalidates everything.
        cache.invalidateAll()
        assertEquals(0, cache.size())
    }

    @Test
    fun `DesktopReloadDeduplicator suppresses watch reload right after socket push`() {
        val dedup = DesktopReloadDeduplicator(watchSuppressWindowMs = 500L)
        assertTrue(dedup.shouldRun("socket:push", nowMs = 1_000L))
        assertFalse(dedup.shouldRun("watch", nowMs = 1_100L))
        assertFalse(dedup.shouldRun("watch", nowMs = 1_499L))
        assertTrue(dedup.shouldRun("watch", nowMs = 1_500L))
        assertTrue(dedup.shouldRun("show", nowMs = 1_050L))
    }

    @Test
    fun `launchCacheNotifiesUi skips redundant UI reload on source toggle and list edit`() {
        assertFalse(launchCacheNotifiesUi(SOURCE_CYCLE_REASON))
        assertFalse(launchCacheNotifiesUi(SOURCE_LIST_EDIT_REASON))
        assertTrue(launchCacheNotifiesUi(STARTUP_REASON))
        assertTrue(launchCacheNotifiesUi("resume:logind"))
        assertTrue(launchCacheNotifiesUi("source_or_location_change"))
    }

    @Test
    fun `loadCached reuses single observation read for cloud actuals and scopes allSourcesHourly to header window`() = runBlocking {
        val dao = openTempDao()
        val lat = 37.4166
        val lon = -122.0889
        val nowMs = 1_795_037_500_000L
        val zone = ZoneId.systemDefault()
        val nowLocal = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDateTime()
        val precipWindow = HeaderContent.precipReserveWindowMs(nowLocal, nowLocal, zone)

        // Seed hourly forecasts: one inside header window, one far outside (5 days back), for 2 sources.
        val insideHourMs = precipWindow.first + 2 * 3_600_000L
        val farPastHourMs = nowMs - 5 * 24 * 3_600_000L
        dao.upsertHourlyForecasts(
            lat, lon, "NWS",
            listOf(
                HourlyForecast(farPastHourMs, 50f, "Clear", precipProbability = 80, source = "NWS", locationLat = lat, locationLon = lon),
                HourlyForecast(insideHourMs, 60f, "Rain", precipProbability = 40, source = "NWS", locationLat = lat, locationLon = lon),
            ),
        )
        dao.upsertHourlyForecasts(
            lat, lon, "OPEN_METEO",
            listOf(
                HourlyForecast(farPastHourMs, 49f, "Clear", precipProbability = 90, source = "OPEN_METEO", locationLat = lat, locationLon = lon),
                HourlyForecast(insideHourMs, 61f, "Rain", precipProbability = 65, source = "OPEN_METEO", locationLat = lat, locationLon = lon),
            ),
        )
        // Seed an NWS METAR observation with cloud cover so MetarCloudBlender produces retroCloudActual.
        val obsTs = nowMs - 30 * 60_000L
        dao.upsertObservations(
            listOf(
                DesktopObservationEntity(
                    stationId = "KNUQ",
                    stationName = "Moffett",
                    timestamp = obsTs,
                    temperature = 58f,
                    condition = "Partly Cloudy",
                    locationLat = lat,
                    locationLon = lon,
                    distanceKm = 3.0f,
                    stationType = StationType.OFFICIAL,
                    fetchedAt = nowMs,
                    api = "NWS",
                    cloudCover = 50,
                    cloudCoverLow = 50,
                    isMetar = true,
                ),
            ),
        )

        val service = DesktopWeatherService(lat, lon, "NWS", emptyMap(), dao)
        val repo = DesktopWeatherRepository(service, dao, lat, lon, "NWS")
        val snapshot = repo.loadCached(now = nowMs)
        assertNotNull(snapshot)
        snapshot!!

        // Cloud actuals populated from the single observation read.
        assertTrue("expected retroCloudActual from single obs read", snapshot.retroCloudActual.isNotEmpty())
        assertEquals(1, snapshot.raw.rawObservations.size)

        // allSourcesHourly contains both sources' near-term hours, NOT the 5-day-old hours.
        val sourcesInAll = snapshot.allSourcesHourly.mapNotNull { it.source }.toSet()
        assertTrue("expected NWS and OPEN_METEO in allSourcesHourly, got $sourcesInAll", setOf("NWS", "OPEN_METEO").all { it in sourcesInAll })
        assertTrue(
            "expected allSourcesHourly scoped to header window, got ${snapshot.allSourcesHourly.map { it.dateTime }}",
            snapshot.allSourcesHourly.all { it.dateTime in precipWindow.first..precipWindow.last },
        )
        service.close()
    }
}
