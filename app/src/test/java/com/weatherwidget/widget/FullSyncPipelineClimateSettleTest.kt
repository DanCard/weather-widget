package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.category.MediumDuration
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Climate normals settle **in parallel with** a sync's fetch and paint, never before them
 * (plans/261122-climate-normals-warm-and-repaint-shared.md). The first fix made the paint wait on the
 * Open-Meteo archive (30 s timeout × 3 attempts); here the archive hangs on a gate and the paint must
 * land while it is still closed, and the worker must not finish until the settle does (structured).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class FullSyncPipelineClimateSettleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lat = 33.645
    private val lon = -111.911

    private val weatherRepository = mockk<WeatherRepository>(relaxed = true)
    private val hourlyForecastLoader = mockk<HourlyForecastLoader>(relaxed = true)
    private val dataBundleLoader = mockk<WidgetDataBundleLoader>(relaxed = true)
    private val painter = mockk<WidgetPaintCoordinator>(relaxed = true)
    private val metarRefresher = mockk<MetarObservationRefresher>(relaxed = true)

    @Before
    fun setUp() {
        ActiveLocationResolver.persist(context, lat, lon)
        coEvery { weatherRepository.getWeatherData(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(listOf(forecastRow()))
        every { hourlyForecastLoader.currentDisplaySourceIds() } returns listOf(WeatherSource.NWS.id)
        every { hourlyForecastLoader.hourlySourceIds() } returns listOf(WeatherSource.NWS.id)
        coEvery { hourlyForecastLoader.load(any(), any(), any()) } returns emptyList<HourlyForecastEntity>()
        coEvery { dataBundleLoader.fetchForecastSnapshots(any(), any()) } returns emptyMap()
        coEvery { metarRefresher.refreshIfDue(any(), any(), any(), any(), any()) } just Runs
    }

    @After
    fun tearDown() = WeatherDatabase.resetInstanceForTesting()

    private fun pipeline() = FullSyncPipeline(
        context = context,
        weatherRepository = weatherRepository,
        widgetStateManager = mockk(relaxed = true),
        appLogDao = mockk(relaxed = true),
        gpsResampler = mockk(relaxed = true),
        hourlyForecastLoader = hourlyForecastLoader,
        dataBundleLoader = dataBundleLoader,
        painter = painter,
        metarRefresher = metarRefresher,
    )

    private fun painted(): Boolean = runCatching {
        coVerify(atLeast = 1) {
            painter.updateAllWidgets(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }.isSuccess

    @Test
    fun `the paint lands while the normals settle is still waiting on the archive`() = runBlocking {
        val archive = CompletableDeferred<Unit>()
        val settleStarted = CompletableDeferred<Unit>()
        coEvery { weatherRepository.settleClimateNormals(any(), any(), any()) } coAnswers {
            settleStarted.complete(Unit)
            archive.await()
            true
        }

        val run = async { pipeline().run(workInput(uiOnly = false), device(), stopReason = 0) }
        withTimeout(10_000) {
            settleStarted.await()
            while (!painted()) kotlinx.coroutines.delay(10)
        }
        println("painted while archive pending: painted=${painted()} runDone=${run.isCompleted}")
        assertFalse("the worker waits for its settle child before finishing", run.isCompleted)

        archive.complete(Unit)
        withTimeout(10_000) { run.await() }
        // The site goes through Float SharedPreferences, so match it within a tolerance.
        coVerify(exactly = 1) {
            weatherRepository.settleClimateNormals(
                match { kotlin.math.abs(it - lat) < 1e-4 }, match { kotlin.math.abs(it - lon) < 1e-4 }, "sync",
            )
        }
    }

    @Test
    fun `a paint-only run never settles normals`() = runBlocking {
        pipeline().run(workInput(uiOnly = true), device(), stopReason = 0)
        assertTrue("fixture: the repaint ran", painted())
        coVerify(exactly = 0) { weatherRepository.settleClimateNormals(any(), any(), any()) }
    }

    private fun forecastRow() = com.weatherwidget.data.local.ForecastEntity(
        targetDate = System.currentTimeMillis(),
        dateOfPrediction = System.currentTimeMillis(),
        highTemp = 70f,
        lowTemp = 50f,
        condition = "Clear",
        source = WeatherSource.NWS.id,
        locationLat = lat,
        locationLon = lon,
    )

    private fun device() = DeviceContext(
        isCharging = true,
        batteryLevel = 90,
        isScreenInteractive = true,
        lastFullFetchAgeSeconds = 3_600L,
    )

    private fun workInput(uiOnly: Boolean) = WorkInput(
        uiOnlyRefresh = uiOnly,
        forceRefresh = false,
        candidateLocationRefresh = false,
        currentTempOnly = false,
        nonPrimaryCurrentTempOnly = false,
        opportunisticCurrentTemp = false,
        currentTempReason = "test",
        targetSourceId = null,
        userInteraction = false,
        observationBackfillMode = false,
        backfillLat = 0.0,
        backfillLon = 0.0,
        backfillHours = 0L,
        backfillReason = "",
    )
}
