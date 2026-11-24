package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.util.TargetedForecastSyncRun
import com.weatherwidget.test.category.MediumDuration
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real [FullSyncPipeline] wired to `:shared` [TargetedForecastSyncRun] through [AndroidTargetedSyncHost]
 * and real WorkManager (plans/261123-shared-targeted-sync-paint-then-tail.md).
 *
 * The 2026-11-23 Pixel trace: a toggle's worker painted, then ran the Synoptic/METAR/NWS backfills and the
 * actuals recompute inline for 6.2 s, so the next toggle could not start on the serial sync queue. These
 * tests pin that a targeted sync returns with none of that work done, with the rain-chance freeze
 * (`dailyHistoryAfterFetch`) still run before the paint, and that the work lands in ONE debounced tail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class TargetedSyncPostActualsIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var weatherRepository: WeatherRepository
    private lateinit var widgetStateManager: WidgetStateManager
    private lateinit var appLogDao: AppLogDao
    private lateinit var hourlyForecastLoader: HourlyForecastLoader
    private lateinit var dataBundleLoader: WidgetDataBundleLoader
    private lateinit var painter: WidgetPaintCoordinator
    private lateinit var metarRefresher: MetarObservationRefresher
    private lateinit var synopticRefresher: SynopticObservationRefresher
    private val order = mutableListOf<String>()

    private val lat = 37.4220
    private val lon = -122.0841

    @Before
    fun setUp() {
        ActiveLocationResolver.persist(context, lat, lon)
        WidgetTargetedSync.run.resetForTesting()
        WidgetBackfillDeferral.policy.resetForTesting()
        workManager().cancelUniqueWork(WidgetWorkScheduler.WORK_NAME_POST_SYNC_ACTUALS).result.get()
        workManager().pruneWork().result.get()

        weatherRepository = mockk(relaxed = true)
        widgetStateManager = mockk(relaxed = true)
        appLogDao = mockk(relaxed = true)
        hourlyForecastLoader = mockk(relaxed = true)
        dataBundleLoader = mockk(relaxed = true)
        painter = mockk(relaxed = true)
        metarRefresher = mockk(relaxed = true)
        synopticRefresher = mockk(relaxed = true)

        coEvery { weatherRepository.getWeatherData(any(), any(), any(), any(), any(), any()) } returns
            Result.success(listOf(forecastRow()))
        coEvery { weatherRepository.dailyHistoryAfterFetch(any(), any()) } answers { order += "afterFetch" }
        coEvery { weatherRepository.backfillNwsObservationsIfNeeded(any(), any(), any(), any()) } answers {
            order += "nwsBackfill"
            null
        }
        coEvery {
            painter.updateAllWidgets(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { order += "paint" }
        every { widgetStateManager.getVisibleSourcesOrder() } returns listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)
        every { hourlyForecastLoader.currentDisplaySourceIds() } returns listOf(WeatherSource.OPEN_METEO.id)
        every { hourlyForecastLoader.hourlySourceIds() } returns listOf(WeatherSource.OPEN_METEO.id)
        coEvery { hourlyForecastLoader.load(any(), any(), any(), any()) } returns emptyList<HourlyForecastEntity>()
        coEvery { dataBundleLoader.fetchForecastSnapshots(any(), any()) } returns emptyMap()
        coEvery { metarRefresher.refreshIfDue(any(), any(), any(), any(), any()) } answers { order += "metar" }
        coEvery { synopticRefresher.refreshIfDue(any(), any(), any(), any(), any()) } answers { order += "synoptic" }
    }

    @After
    fun tearDown() {
        workManager().cancelUniqueWork(WidgetWorkScheduler.WORK_NAME_POST_SYNC_ACTUALS).result.get()
        WidgetTargetedSync.run.resetForTesting()
        WeatherDatabase.resetInstanceForTesting()
    }

    @Test
    fun `a targeted toggle sync paints after the rain-chance freeze and returns with no backfill or recompute`() = runBlocking {
        val result = pipeline().run(targeted("toggle_api_stale", WeatherSource.OPEN_METEO.id), device(), stopReason = 0)
        println("order=$order result=$result")

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("freeze first, then paint, nothing slow", listOf("afterFetch", "paint"), order)
        coVerify(exactly = 0) { metarRefresher.refreshIfDue(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { synopticRefresher.refreshIfDue(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { weatherRepository.backfillNwsObservationsIfNeeded(any(), any(), any(), any()) }
        coVerify(exactly = 0) { weatherRepository.repairFrozenRainChanceIfNeeded(any(), any()) }
        val recompute = slot<Boolean>()
        coVerify(exactly = 1) { dataBundleLoader.fetchDailyActuals(any(), any(), any(), any(), capture(recompute)) }
        assertFalse("the paint reads cached actuals", recompute.captured)

        val tail = tailWork().single()
        println("tail state=${tail.state} delay=${tail.initialDelayMillis}")
        assertEquals(WorkInfo.State.ENQUEUED, tail.state)
        assertEquals(TargetedForecastSyncRun.TAIL_DELAY_MS, tail.initialDelayMillis)
    }

    @Test
    fun `three toggles in a row leave one tail`() = runBlocking {
        for (source in listOf(WeatherSource.GOOGLE_WEATHER, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN)) {
            pipeline().run(targeted("toggle_api_stale", source.id), device(), stopReason = 0)
        }
        val unfinished = tailWork().filterNot { it.state.isFinished }
        println("tails=${unfinished.map { it.state }}")
        assertEquals(1, unfinished.size)
    }

    @Test
    fun `Settings refresh of every source keeps the inline backfills and asks for no tail`() = runBlocking {
        pipeline().run(targeted("manual_refresh", targetSourceId = null), device(), stopReason = 0)
        println("order=$order")

        assertEquals(listOf("nwsBackfill", "metar", "synoptic", "afterFetch", "paint"), order)
        assertTrue(tailWork().none { !it.state.isFinished })
    }

    @Test
    fun `the tail fills observations, recomputes, and repaints only when the displayed actuals changed`() = runBlocking {
        val unchanged = mapOf(WeatherSource.OPEN_METEO.id to emptyMap<java.time.LocalDate, com.weatherwidget.data.model.DailyHistory>())
        coEvery { dataBundleLoader.fetchDailyActuals(any(), any(), any(), any(), any()) } answers {
            order += if (arg<Boolean>(4)) "recompute" else "read"
            unchanged
        }
        // Counted at the host, not as WorkManager state: the UI work may already have run and finished.
        var repaints = 0
        val real = host()
        val counting = object : TargetedForecastSyncRun.Host by real {
            override suspend fun repaint(reason: String) {
                repaints++
                real.repaint(reason)
            }
        }

        val outcome = WidgetTargetedSync.run.runTail(counting, setOf(WeatherSource.OPEN_METEO.id))
        println("outcome=$outcome order=$order")

        assertEquals(TargetedForecastSyncRun.TailOutcome.RAN, outcome)
        assertEquals(listOf("read", "nwsBackfill", "metar", "synoptic", "recompute", "read"), order)
        val served = slot<Collection<WeatherSource>>()
        coVerify { weatherRepository.backfillNwsObservationsIfNeeded(any(), any(), capture(served), any()) }
        assertEquals(setOf(WeatherSource.NWS, WeatherSource.OPEN_METEO), served.captured.toSet())
        coVerify(exactly = 1) { weatherRepository.repairFrozenRainChanceIfNeeded(any(), any()) }
        assertEquals("unchanged actuals: no repaint", 0, repaints)

        // Now the recompute changes what the widget draws.
        var reads = 0
        coEvery { dataBundleLoader.fetchDailyActuals(any(), any(), any(), any(), any()) } answers {
            if (arg<Boolean>(4)) unchanged else if (reads++ == 0) unchanged else emptyMap()
        }
        WidgetTargetedSync.run.runTail(counting, setOf(WeatherSource.OPEN_METEO.id))
        assertEquals("changed actuals: one repaint", 1, repaints)
        assertTrue("the repaint reached WorkManager", uiWork().isNotEmpty())
    }

    @Test
    fun `the tail yields while a user sync runs`() = runBlocking {
        WidgetBackfillDeferral.policy.userSyncStarted()
        try {
            val outcome = WidgetTargetedSync.run.runTail(host(), setOf(WeatherSource.OPEN_METEO.id))
            println("outcome=$outcome tails=${tailWork().map { it.state to it.initialDelayMillis }}")
            assertEquals(TargetedForecastSyncRun.TailOutcome.YIELDED, outcome)
            coVerify(exactly = 0) { metarRefresher.refreshIfDue(any(), any(), any(), any(), any()) }
            assertEquals(TargetedForecastSyncRun.YIELD_DELAY_MS, tailWork().single { !it.state.isFinished }.initialDelayMillis)
        } finally {
            WidgetBackfillDeferral.policy.userSyncEnded()
        }
    }

    private fun workManager() = WorkManager.getInstance(context)

    private fun tailWork() =
        workManager().getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_POST_SYNC_ACTUALS).get()

    private fun uiWork() = workManager().getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_UI).get()

    private fun host() = AndroidTargetedSyncHost(
        context, weatherRepository, widgetStateManager, appLogDao, hourlyForecastLoader, dataBundleLoader,
        metarRefresher, synopticRefresher, lat to lon,
    )

    private fun pipeline() = FullSyncPipeline(
        context = context,
        weatherRepository = weatherRepository,
        widgetStateManager = widgetStateManager,
        appLogDao = appLogDao,
        gpsResampler = mockk(relaxed = true),
        hourlyForecastLoader = hourlyForecastLoader,
        dataBundleLoader = dataBundleLoader,
        painter = painter,
        metarRefresher = metarRefresher,
        synopticRefresher = synopticRefresher,
    )

    private fun forecastRow() = com.weatherwidget.data.local.ForecastEntity(
        targetDate = System.currentTimeMillis(),
        dateOfPrediction = System.currentTimeMillis(),
        highTemp = 70f,
        lowTemp = 50f,
        condition = "Clear",
        source = WeatherSource.OPEN_METEO.id,
        locationLat = lat,
        locationLon = lon,
    )

    private fun device() = DeviceContext(
        isCharging = true,
        batteryLevel = 90,
        isScreenInteractive = true,
        lastFullFetchAgeSeconds = 3_600L,
    )

    private fun targeted(reason: String, targetSourceId: String?) = WorkInput(
        uiOnlyRefresh = false,
        forceRefresh = true,
        candidateLocationRefresh = false,
        currentTempOnly = false,
        nonPrimaryCurrentTempOnly = false,
        opportunisticCurrentTemp = false,
        currentTempReason = reason,
        targetSourceId = targetSourceId,
        userInteraction = true,
        observationBackfillMode = false,
        backfillLat = 0.0,
        backfillLon = 0.0,
        backfillHours = 0L,
        backfillReason = "",
    )
}
