package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.actuals.DailyBlendRecomputeRun
import com.weatherwidget.shared.actuals.InMemoryReducedSignatureStore
import com.weatherwidget.shared.observations.NwsGapFillRun
import com.weatherwidget.shared.observations.ObservationBackfillRetryPolicy
import com.weatherwidget.shared.observations.ObservationWriteRun
import com.weatherwidget.shared.observations.StationNetworkRefreshRun
import com.weatherwidget.shared.util.TargetedForecastSyncRun
import com.weatherwidget.test.category.MediumDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
import java.time.LocalDate

/**
 * The real [FullSyncPipeline] wired to `:shared` [TargetedForecastSyncRun] through [AndroidTargetedSyncHost]
 * and real WorkManager (plans/261123-shared-targeted-sync-paint-then-tail.md,
 * plans/261123-targeted-sync-tail-parity.md).
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
    private val order = mutableListOf<String>()

    private val lat = 37.4220
    private val lon = -122.0841

    private fun fakeStationHost() = object : StationNetworkRefreshRun.Host {
        override val lat = this@TargetedSyncPostActualsIntegrationTest.lat
        override val lon = this@TargetedSyncPostActualsIntegrationTest.lon
        override suspend fun backgroundSources() = listOf(WeatherSource.NWS, WeatherSource.SILURIAN)
        override suspend fun displayedSourceIds() = setOf(WeatherSource.OPEN_METEO.id)
        override fun actualsPreference(source: WeatherSource) =
            if (source == WeatherSource.SILURIAN) WeatherSource.METAR else null
        override suspend fun newestStoredTimestampMs(api: String): Long? = null
        override suspend fun fetchMetar(hours: Int): List<ObservationReading> {
            order += "metar:$hours"
            return emptyList()
        }
        override fun writeHost(): ObservationWriteRun.Host = object : ObservationWriteRun.Host {
            override val latitude = lat
            override val longitude = lon
            override suspend fun insert(rows: List<ObservationReading>) = Unit
            override suspend fun storedKeys(api: String, stationIds: Set<String>, startMs: Long, endMs: Long) = emptyList<ObservationWriteRun.StoredKey>()
            override suspend fun delete(api: String, stationId: String, timestamps: List<Long>) = 0
            override suspend fun retag(api: String, stationIds: Set<String>, stationType: StationType) = 0
            override suspend fun retireProducts(api: String) = Unit
            override suspend fun log(tag: String, message: String, level: String) = Unit
        }
        override suspend fun log(tag: String, message: String, level: String) = Unit
    }

    private fun fakeBlendHost() = object : DailyBlendRecomputeRun.Host {
        override val lat = this@TargetedSyncPostActualsIntegrationTest.lat
        override val lon = this@TargetedSyncPostActualsIntegrationTest.lon
        private var recorded = false
        override fun personalStationWeight() = 0.5
        override suspend fun observations(startMs: Long, endMs: Long) = emptyList<ObservationReading>()
        override suspend fun hourlyAllSources(startMs: Long, endMs: Long) = emptyList<HourlyForecast>()
        override suspend fun observationSignature(startMs: Long, endMs: Long): String? {
            if (!recorded) {
                recorded = true
                order += "recompute"
            }
            return null
        }
        override suspend fun dailyHistoryAt(dateKey: Long) = emptyList<DailyHistory>()
        override suspend fun insert(rows: List<DailyHistory>) = Unit
        override suspend fun updateBlendIfUnchanged(merged: DailyHistory, newUpdatedAt: Long, expectedUpdatedAt: Long) = 0
        override val signatures = InMemoryReducedSignatureStore()
        override suspend fun log(tag: String, message: String, level: String) = Unit
        override fun elapsedMs() = 0L
    }

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

        coEvery { weatherRepository.getWeatherData(any(), any(), any(), any(), any(), any()) } returns
            Result.success(listOf(forecastRow()))
        coEvery { weatherRepository.dailyHistoryAfterFetch(any(), any()) } answers { order += "afterFetch" }
        coEvery { weatherRepository.backfillNwsObservationsIfNeeded(any(), any(), any(), any()) } answers {
            order += "nwsBackfill"
            null
        }
        every { weatherRepository.blendRecomputeHost(any(), any()) } answers { fakeBlendHost() }
        coEvery { weatherRepository.getDailyHistoryInRange(any(), any(), any(), any()) } answers {
            order += "read"
            emptyList()
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
        every { metarRefresher.hostFor(any(), any()) } answers { fakeStationHost() }
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
    fun `a failed targeted fetch still paints from cache but skips afterFetch and schedules no tail`() = runBlocking {
        every { widgetStateManager.isSourceErrored(WeatherSource.OPEN_METEO) } returns true

        val result = pipeline().run(targeted("viewing_forecast_stale", WeatherSource.OPEN_METEO.id), device(), stopReason = 0)
        println("failed targeted order=$order result=$result")

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        assertEquals("paint only, no afterFetch when the targeted source failed", listOf("paint"), order)
        assertTrue("no tail scheduled for a failed targeted fetch", tailWork().none { !it.state.isFinished })
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

        assertEquals(listOf("nwsBackfill", "metar", "afterFetch", "paint"), order)
        assertTrue(tailWork().none { !it.state.isFinished })
    }

    @Test
    fun `the tail runs shared station refresh, 9-day recompute, and repaints only when daily_history changed`() = runBlocking {
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
        assertEquals(listOf("read", "nwsBackfill", "metar:24", "recompute", "read"), order)
        val served = slot<Collection<WeatherSource>>()
        coVerify { weatherRepository.backfillNwsObservationsIfNeeded(any(), any(), capture(served), any()) }
        assertEquals(setOf(WeatherSource.NWS, WeatherSource.OPEN_METEO), served.captured.toSet())
        coVerify(exactly = 1) { weatherRepository.repairFrozenRainChanceIfNeeded(any(), any()) }
        assertEquals("unchanged actuals: no repaint", 0, repaints)

        // Now the recompute changes daily_history.
        var reads = 0
        coEvery { weatherRepository.getDailyHistoryInRange(any(), any(), any(), any()) } answers {
            if (reads++ == 0) emptyList() else listOf(
                DailyHistory(
                    date = 0L, source = "NWS", locationLat = lat, locationLon = lon,
                    computedHighTemp = 72f, computedLowTemp = 50f, condition = "Clear", updatedAt = 1L,
                ),
            )
        }
        WidgetTargetedSync.run.runTail(counting, setOf(WeatherSource.OPEN_METEO.id))
        assertEquals("changed actuals: one repaint", 1, repaints)
        assertTrue("the repaint reached WorkManager", uiWork().isNotEmpty())
    }

    @Test
    fun `unreachable history fill enqueues a 10s retry tail in WorkManager`() = runBlocking {
        coEvery { weatherRepository.backfillNwsObservationsIfNeeded(any(), any(), any(), any()) } returns
            NwsGapFillRun.Result(
                needed = setOf(LocalDate.now()),
                remaining = setOf(LocalDate.now()),
                stationsTried = 0,
                rows = 0,
                unreachable = true,
            )

        WidgetTargetedSync.run.runTail(host(), setOf(WeatherSource.NWS.id))

        val retry = tailWork().single { !it.state.isFinished }
        assertEquals(WorkInfo.State.ENQUEUED, retry.state)
        assertEquals(ObservationBackfillRetryPolicy.RETRY_DELAYS_MS[0], retry.initialDelayMillis)
    }

    @Test
    fun `the tail yields while a user sync runs`() = runBlocking {
        WidgetBackfillDeferral.policy.userSyncStarted()
        try {
            val outcome = WidgetTargetedSync.run.runTail(host(), setOf(WeatherSource.OPEN_METEO.id))
            println("outcome=$outcome tails=${tailWork().map { it.state to it.initialDelayMillis }}")
            assertEquals(TargetedForecastSyncRun.TailOutcome.YIELDED, outcome)
            assertTrue("no station fetch while yielded", order.none { it.startsWith("metar") })
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
        context, weatherRepository, widgetStateManager, appLogDao, metarRefresher, lat to lon,
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
