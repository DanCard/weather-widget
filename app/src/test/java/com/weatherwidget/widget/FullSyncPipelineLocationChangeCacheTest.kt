package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.category.MediumDuration
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A setup-screen change to a site whose cache already has today's row: the forced sync paints that
 * cache BEFORE it fetches, and then fetches only what is stale. 2026-09-29 (Pixel 7 Pro): Warsaw →
 * Mountain View with MV's rows 15 min old kept Warsaw's graph under "Getting weather for Mountain
 * View…" for the 60 s a forced refetch of all six sources took.
 *
 * Both directions: with no row for today the sync must keep today's behaviour (no early paint,
 * force=true), or the "adopted" assertions would pass against a pipeline that never forces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class FullSyncPipelineLocationChangeCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var weatherRepository: WeatherRepository
    private lateinit var painter: WidgetPaintCoordinator
    private lateinit var hourlyForecastLoader: HourlyForecastLoader
    private lateinit var dataBundleLoader: WidgetDataBundleLoader
    private val appLogDao: AppLogDao = mockk(relaxed = true)

    private val lat = 37.4166
    private val lon = -122.0889

    @Before
    fun setUp() {
        ActiveLocationResolver.persist(context, lat, lon)
        weatherRepository = mockk(relaxed = true)
        painter = mockk(relaxed = true)
        hourlyForecastLoader = mockk(relaxed = true)
        dataBundleLoader = mockk(relaxed = true)
        coEvery {
            weatherRepository.getWeatherData(any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(listOf(forecastRow()))
        every { hourlyForecastLoader.currentDisplaySourceIds() } returns listOf(WeatherSource.OPEN_METEO.id)
        every { hourlyForecastLoader.hourlySourceIds() } returns listOf(WeatherSource.OPEN_METEO.id)
        coEvery { hourlyForecastLoader.load(any(), any(), any()) } returns emptyList<HourlyForecastEntity>()
        coEvery { dataBundleLoader.fetchForecastSnapshots(any(), any()) } returns emptyMap()
    }

    @After
    fun tearDown() {
        WeatherDatabase.resetInstanceForTesting()
    }

    private fun pipeline() = FullSyncPipeline(
        context = context,
        weatherRepository = weatherRepository,
        widgetStateManager = mockk(relaxed = true),
        appLogDao = appLogDao,
        gpsResampler = mockk(relaxed = true),
        hourlyForecastLoader = hourlyForecastLoader,
        dataBundleLoader = dataBundleLoader,
        painter = painter,
        metarRefresher = mockk<MetarObservationRefresher>(relaxed = true).also {
            coEvery { it.refreshIfDue(any(), any(), any(), any(), any()) } just Runs
        },
        synopticRefresher = mockk<SynopticObservationRefresher>(relaxed = true).also {
            coEvery { it.refreshIfDue(any(), any(), any(), any(), any()) } just Runs
        },
    )

    private data class Fetch(val force: Boolean, val context: ForecastFetchContext?)

    private suspend fun runBannerSync(cacheAdopted: Boolean): Fetch {
        coEvery { painter.adoptCachedNewSite(any(), any(), any(), any(), any()) } returns cacheAdopted
        pipeline().run(bannerInput(), device(), stopReason = 0)
        val force = slot<Boolean>()
        val fetchContext = mutableListOf<ForecastFetchContext?>()
        coVerify(exactly = 1) {
            weatherRepository.getWeatherData(any(), any(), capture(force), any(), any(), captureNullable(fetchContext), any())
        }
        return Fetch(force.captured, fetchContext.single())
    }

    @Test
    fun `cached new site is painted before the fetch, which then runs unforced`() = runBlocking {
        val fetch = runBannerSync(cacheAdopted = true)

        coVerifyOrder {
            painter.adoptCachedNewSite("Mountain View", any(), any(), any(), any())
            weatherRepository.getWeatherData(any(), any(), any(), any(), any(), any(), any())
        }
        assertFalse("fresh cache must not trigger a forced refetch of every source", fetch.force)
        assertNotNull("unforced fetch must be scoped by the battery/freshness context", fetch.context)
        // The adoption already cleared the banner; the sync's end must not touch it again.
        coVerify(exactly = 0) { painter.finishLocationChangeBanner(any(), any(), any()) }
    }

    @Test
    fun `a replay whose deferral already adopted the cache skips the probe and runs unforced`() = runBlocking {
        // 2026-09-29 22:24:45: the replay re-probed and repainted Kyiv (4.7 s) after the deferral
        // had already drawn it and dropped the banner.
        pipeline().run(bannerInput().copy(locationCacheAdopted = true), device(), stopReason = 0)

        coVerify(exactly = 0) { painter.adoptCachedNewSite(any(), any(), any(), any(), any()) }
        val force = slot<Boolean>()
        coVerify { weatherRepository.getWeatherData(any(), any(), capture(force), any(), any(), any(), any()) }
        assertFalse(force.captured)
        coVerify(exactly = 0) { painter.finishLocationChangeBanner(any(), any(), any()) }
    }

    @Test
    fun `new site without today's row keeps the forced fetch`() = runBlocking {
        val fetch = runBannerSync(cacheAdopted = false)

        assertTrue(fetch.force)
        assertNull(fetch.context)
        coVerify(exactly = 1) { painter.finishLocationChangeBanner("Mountain View", succeeded = true, reason = "sync_success") }
    }

    @Test
    fun `a failed refresh after adopting the cache paints no failure over it`() = runBlocking {
        coEvery {
            weatherRepository.getWeatherData(any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(java.io.IOException("offline"))

        runBannerSync(cacheAdopted = true)

        coVerify(exactly = 0) { painter.finishLocationChangeBanner(any(), succeeded = false, any()) }
    }

    @Test
    fun `interstitial changes never probe for a banner adoption`() = runBlocking {
        pipeline().run(bannerInput().copy(locationChangeBanner = false), device(), stopReason = 0)

        coVerify(exactly = 0) { painter.adoptCachedNewSite(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { painter.paintLocationChangeInterstitial("Mountain View", any(), any(), any()) }
        val force = slot<Boolean>()
        coVerify { weatherRepository.getWeatherData(any(), any(), capture(force), any(), any(), any(), any()) }
        assertEquals(true, force.captured)
    }

    // Source switch (plans/261006-source-becomes-primary-fetch-and-banner.md): the run clears its
    // "Getting weather from {source}…" banner at every exit, success or failure.
    private fun sourceSwitchInput() = bannerInput().copy(
        locationChangePlace = null,
        locationChangeBanner = false,
        targetSourceId = WeatherSource.GOOGLE_WEATHER.id,
        sourceSwitchId = WeatherSource.GOOGLE_WEATHER.id,
    )

    @Test
    fun `a source switch's successful sync clears its banner`() = runBlocking {
        pipeline().run(sourceSwitchInput(), device(), stopReason = 0)

        coVerify(exactly = 1) { painter.finishSourceSwitchBanner(WeatherSource.GOOGLE_WEATHER.id, succeeded = true, reason = "sync_success") }
        coVerify(exactly = 0) { painter.finishLocationChangeBanner(any(), any(), any()) }
    }

    @Test
    fun `a source switch's failed sync still clears its banner`() = runBlocking {
        coEvery {
            weatherRepository.getWeatherData(any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(java.io.IOException("offline"))

        pipeline().run(sourceSwitchInput(), device(), stopReason = 0)

        coVerify(exactly = 1) { painter.finishSourceSwitchBanner(WeatherSource.GOOGLE_WEATHER.id, succeeded = false, reason = "sync_failure") }
    }

    @Test
    fun `an ordinary sync touches no source-switch banner`() = runBlocking {
        pipeline().run(sourceSwitchInput().copy(sourceSwitchId = null), device(), stopReason = 0)

        coVerify(exactly = 0) { painter.finishSourceSwitchBanner(any(), any(), any()) }
    }

    private fun forecastRow() = ForecastEntity(
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
        batteryLevel = 96,
        isScreenInteractive = true,
        lastFullFetchAgeSeconds = 900L,
    )

    private fun bannerInput() = WorkInput(
        uiOnlyRefresh = false,
        forceRefresh = true,
        candidateLocationRefresh = false,
        currentTempOnly = false,
        nonPrimaryCurrentTempOnly = false,
        opportunisticCurrentTemp = false,
        currentTempReason = "unspecified",
        targetSourceId = null,
        userInteraction = false,
        observationBackfillMode = false,
        backfillLat = Double.NaN,
        backfillLon = Double.NaN,
        backfillHours = 0L,
        backfillReason = "",
        noHourlyWidgetId = 0,
        noHourlyDate = null,
        noHourlyLat = 0.0,
        noHourlyLon = 0.0,
        shouldBroadcastNoHourlyComplete = false,
        locationChangePlace = "Mountain View",
        locationChangeBanner = true,
    )
}
