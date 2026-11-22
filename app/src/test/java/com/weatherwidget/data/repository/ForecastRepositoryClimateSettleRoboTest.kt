package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.ClimateNormals
import com.weatherwidget.shared.util.StoredDataVersion
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import androidx.work.WorkManager
import com.weatherwidget.widget.WidgetWorkScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Integration: `:shared` ClimateNormalsRun.ensureForSite through [ForecastRepository.settleClimateNormals],
 * [ClimateNormalsRepository] and [RoomClimateNormalsHost] over real Room, with the real WorkManager
 * repaint.
 *
 * The 2026-11-22 round-trip, Android's half: normals are kept for the current site only, so a return to
 * a site with a fresh forecast found none, and Android warmed only after a network fetch. The settle now
 * runs in parallel with the sync (FullSyncPipeline; see FullSyncPipelineClimateSettleTest), so the sync
 * itself never touches the archive, and newly cached normals enqueue a paint-only repaint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class ForecastRepositoryClimateSettleRoboTest {
    private val lat = 33.645
    private val lon = -111.911
    private val today: LocalDate = LocalDate.now()

    private lateinit var db: WeatherDatabase
    private lateinit var repository: ForecastRepository
    private val openMeteoApi = mockk<com.weatherwidget.data.remote.OpenMeteoApi>(relaxed = true)
    private var archiveCounter = 0

    @Before
    fun setUp() {
        db = TestDatabase.create()
        coEvery { openMeteoApi.getHistoricalDailyTemps(any(), any(), any(), any()) } answers {
            archiveCounter++
            (1..12).map { m -> DailyForecast("2025-%02d-15".format(m), 69f, 47f, "Clear") }
        }
        val stateManager = mockk<WidgetStateManager>(relaxed = true)
        every { stateManager.getVisibleSourcesOrder() } returns listOf(WeatherSource.NWS)
        every { stateManager.isSourceVisible(any()) } returns true
        repository = ForecastRepository(
            context = RuntimeEnvironment.getApplication(),
            forecastDao = db.forecastDao(),
            hourlyForecastDao = db.hourlyForecastDao(),
            hourlyForecastHistoryDao = db.hourlyForecastHistoryDao(),
            appLogDao = db.appLogDao(),
            nwsApi = mockk(relaxed = true),
            openMeteoApi = openMeteoApi,
            weatherApi = mockk(relaxed = true),
            silurianApi = mockk(relaxed = true),
            widgetStateManager = stateManager,
            climateNormalDao = db.climateNormalDao(),
            observationDao = db.observationDao(),
            dailyHistoryDao = db.dailyHistoryDao(),
            observationRepository = mockk(relaxed = true),
            tomorrowIoApi = mockk(relaxed = true),
            openWeatherMapApi = mockk(relaxed = true),
            nwsForecastMapper = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun climateDays(rows: List<com.weatherwidget.data.local.ForecastEntity>) =
        rows.filter { it.isClimateNormal }.map { LocalDate.ofEpochDay(it.targetDate / 86_400_000L) }.toSet()

    @Test
    fun `back at a site whose normals were dropped, the sync serves the cache and the settle re-caches and repaints`() = runBlocking {
        // NWS-shaped reach at the site: today .. today + 6.
        db.forecastDao().insertAll(
            (0..6).map { d ->
                val date = today.plusDays(d.toLong()).toString()
                TestData.forecast(targetDate = date, dateOfPrediction = today.toString(), source = "NWS", lat = lat, lon = lon)
            },
        )
        // The first visit: a fetch, with its parallel settle caching this site's normals.
        repository.getWeatherData(lat, lon, forceRefresh = true)
        val completedAt = db.appLogDao().getLogsByTag("NET_FETCH_COMPLETE", 10).single().timestamp
        assertTrue(repository.settleClimateNormals(lat, lon, reason = "sync"))

        // A visit elsewhere: retention keeps only that site's normals.
        RoomClimateNormalsHost(db.climateNormalDao(), db.appLogDao(), fetch = { _, _ -> emptyList() }, onCached = {})
            .store(ClimateNormals.locationKey(37.417, -122.089), mapOf(11 to 60f), mapOf(11 to 44f))
        assertTrue("fixture: this site's normals dropped", db.climateNormalDao().getNormalsForLocation(ClimateNormals.locationKey(lat, lon)).isEmpty())

        // Back here: the forecast is fresh, so the sync serves the cache — and never waits on the archive.
        val archiveCallsBefore = archiveCalls()
        val served = repository.getWeatherData(lat, lon, forceRefresh = true, requestedAtMs = completedAt - 5_000).getOrThrow()
        assertEquals("the sync itself never fetches normals", archiveCallsBefore, archiveCalls())
        assertTrue("so its rows have no fallback yet", today.plusDays(7) !in climateDays(served))

        // The pipeline's parallel settle.
        val versionBefore = StoredDataVersion.process.current()
        assertTrue(repository.settleClimateNormals(lat, lon, reason = "sync"))

        val filled = climateDays(repository.getCachedData(lat, lon))
        val repaints = WorkManager.getInstance(RuntimeEnvironment.getApplication())
            .getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_UI).get()
        println("after settle: climateDays=${filled.sorted().take(3)}… uiRepaints=${repaints.map { it.state }}")
        assertTrue("today + 7 (past the forecast's reach) is a climate day", today.plusDays(7) in filled)
        assertTrue("a paint-only repaint was enqueued", repaints.isNotEmpty())
        assertTrue("stored-data version bumped for in-memory readers", StoredDataVersion.process.current() > versionBefore)
        assertTrue(
            "CLIMATE_REPAINT logged with its reason",
            db.appLogDao().getLogsByTag("CLIMATE_REPAINT", 10).any { it.message.contains("reason=sync") },
        )
        assertFalse("a second settle finds the cache", repository.settleClimateNormals(lat, lon, reason = "sync"))
    }

    @Test
    fun `no sync path fetches normals by itself, forced or not`() = runBlocking {
        db.forecastDao().insertAll(
            listOf(TestData.forecast(targetDate = today.toString(), dateOfPrediction = today.toString(), lat = lat, lon = lon)),
        )
        repository.getWeatherData(lat, lon, networkAllowed = false)
        repository.getWeatherData(lat, lon, forceRefresh = true)
        assertEquals(0, archiveCalls())
        assertTrue(db.climateNormalDao().getNormalsForLocation(ClimateNormals.locationKey(lat, lon)).isEmpty())
    }

    private fun archiveCalls(): Int = archiveCounter
}
