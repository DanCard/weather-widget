package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.WeatherSource.NWS
import com.weatherwidget.data.model.WeatherSource.OPEN_METEO
import com.weatherwidget.data.model.WeatherSource.WEATHER_API
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.SourceQuotaBlocks
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.ForecastFetchContext
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
 * Android's sync plan under the one horizon rule: a source is fetched (both parts) when its LONG or
 * SHORT horizon is due by its markers, the same for the displayed source and every other one; quota
 * blocks drop a product; a force fetches both. performance/261016-unified-long-short-forecast-horizons.md
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class ForecastHorizonPlanRoboTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: WeatherDatabase
    private val lat = 37.42
    private val lon = -122.08
    private val hour = 3_600_000L
    private val now = System.currentTimeMillis()

    private val lastLong = mutableMapOf<String, Long?>()
    private val lastShort = mutableMapOf<String, Long?>()
    private val wsm = mockk<WidgetStateManager>(relaxed = true)
    private val openMeteo = mockk<OpenMeteoApi>()
    private val weatherApi = mockk<WeatherApi>()
    private val snapshotStore = mockk<ForecastSnapshotStore>(relaxed = true)

    @Before
    fun setup() {
        WeatherDatabase.setIsTesting(true)
        database = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(database)
        every { wsm.getVisibleSourcesOrder() } returns listOf(NWS, OPEN_METEO, WEATHER_API)
        every { wsm.isSourceVisible(any()) } answers { firstArg<WeatherSource>() in listOf(NWS, OPEN_METEO, WEATHER_API) }
        every { wsm.getLastLongFetch(any(), any(), any()) } answers { lastLong[firstArg()] }
        every { wsm.getLastShortFetch(any(), any(), any()) } answers { lastShort[firstArg()] }
        val raw = RawFetch(
            daily = listOf(DailyForecast(date = "2026-10-12", highTemp = 70f, lowTemp = 50f, condition = "Sunny")),
            hourly = listOf(HourlyForecast(dateTime = now + hour, temperature = 60f, condition = "Sunny")),
        )
        coEvery { openMeteo.getForecast(any(), any(), any(), any(), any()) } returns raw
        coEvery { openMeteo.getPriorDayCloudForecast(any(), any(), any(), any()) } returns emptyMap()
        coEvery { weatherApi.getForecast(any(), any(), any()) } returns raw
    }

    @After
    fun teardown() {
        SourceQuotaBlocks.reset()
        WeatherDatabase.resetInstanceForTesting()
        WeatherDatabase.setIsTesting(false)
    }

    private fun coordinator() = ForecastFetchCoordinator(
        context = context,
        appLogDao = mockk(relaxed = true),
        openMeteoApi = openMeteo,
        weatherApi = weatherApi,
        silurianApi = mockk(relaxed = true),
        widgetStateManager = wsm,
        tomorrowIoApi = null,
        openWeatherMapApi = null,
        nwsForecastMapper = mockk(relaxed = true),
        snapshotStore = snapshotStore,
        hourlyStore = mockk(relaxed = true),
        weatherApiHistoryBackfiller = mockk(relaxed = true),
        nwsApiDailyActualsFetcher = mockk(relaxed = true),
        clock = { now },
    )

    /** NWS displayed, screen on; battery 85 % unless given. */
    private fun ctx(battery: Int = 85, screenOn: Boolean = true) =
        ForecastFetchContext(isCharging = false, isScreenInteractive = screenOn, batteryLevel = battery, activeSourceIds = setOf(NWS.id))

    private val noRows = emptyList<ForecastEntity>()

    private fun plan(c: ForecastFetchContext = ctx(), force: Boolean = false) =
        coordinator().planFetch(noRows, force, null, c, null, lat, lon)

    private fun fetchedAgo(source: WeatherSource, longH: Long, shortH: Long) {
        lastLong[source.id] = now - longH * hour
        lastShort[source.id] = now - shortH * hour
    }

    private fun allFresh() = listOf(NWS, OPEN_METEO, WEATHER_API).forEach { fetchedAgo(it, 1, 1) }

    @Test
    fun `nothing due plans nothing and needs no network`() {
        allFresh()
        assertEquals(emptyMap<WeatherSource, ForecastParts>(), plan())
        assertFalse(coordinator().requiresNetworkFetch(noRows, ctx(), null, lat, lon))
    }

    @Test
    fun `a never-fetched source is due at once`() {
        allFresh()
        lastLong[OPEN_METEO.id] = null
        lastShort[OPEN_METEO.id] = null
        assertEquals(mapOf(OPEN_METEO to ForecastParts.BOTH), plan())
    }

    @Test
    fun `a source not on screen follows the same rule as the displayed one`() {
        allFresh()
        fetchedAgo(NWS, longH = 6, shortH = 5)
        fetchedAgo(OPEN_METEO, longH = 6, shortH = 5)
        assertEquals(mapOf(NWS to ForecastParts.BOTH, OPEN_METEO to ForecastParts.BOTH), plan())
    }

    @Test
    fun `the FETCH_PLAN line judges only the sources the gate lets through`() {
        // Was every visible source, gated-off ones included; desktop judged only its candidates.
        allFresh()
        val line = coordinator().planSync(noRows, false, null, ctx(), setOf(NWS), lat, lon).line
        assertTrue(line, line.contains("NWS:"))
        assertFalse(line, line.contains("OPEN_METEO:"))
        assertFalse(line, line.contains("WEATHER_API:"))
    }

    @Test
    fun `screen off waits 8 h for SHORT`() {
        allFresh()
        fetchedAgo(NWS, longH = 6, shortH = 5)
        assertEquals(emptyMap<WeatherSource, ForecastParts>(), plan(ctx(screenOn = false)))
        fetchedAgo(NWS, longH = 9, shortH = 8)
        assertEquals(mapOf(NWS to ForecastParts.BOTH), plan(ctx(screenOn = false)))
    }

    @Test
    fun `below 70 percent only a day-old LONG is due`() {
        allFresh()
        fetchedAgo(NWS, longH = 23, shortH = 10)
        assertEquals(emptyMap<WeatherSource, ForecastParts>(), plan(ctx(battery = 60)))
        fetchedAgo(NWS, longH = 24, shortH = 1)
        assertEquals(mapOf(NWS to ForecastParts.BOTH), plan(ctx(battery = 60)))
    }

    /** performance/261012-forecast-loop-quota-blocked-part-is-not-due.md */
    @Test
    fun `a quota-blocked product is dropped, a fully blocked source not planned`() {
        allFresh()
        fetchedAgo(NWS, longH = 13, shortH = 13)
        SourceQuotaBlocks.block(NWS.id, ForecastProduct.HOURLY, now + 3 * hour)
        assertEquals(mapOf(NWS to ForecastParts.DAILY), plan())
        SourceQuotaBlocks.block(NWS.id, ForecastProduct.DAILY, now + 3 * hour)
        assertEquals(emptyMap<WeatherSource, ForecastParts>(), plan())
        assertFalse(coordinator().requiresNetworkFetch(noRows, ctx(), null, lat, lon))
    }

    @Test
    fun `an untargeted force fetches every source, both parts`() {
        allFresh()
        assertEquals(setOf(NWS, OPEN_METEO, WEATHER_API), plan(force = true).keys)
        assertEquals(setOf(ForecastParts.BOTH), plan(force = true).values.toSet())
    }

    @Test
    fun `requiresNetworkFetch wakes for a SHORT horizon`() {
        allFresh()
        fetchedAgo(NWS, longH = 5, shortH = 4)
        assertTrue(coordinator().requiresNetworkFetch(noRows, ctx(), null, lat, lon))
    }

    @Test
    fun `a SHORT fetch marks SHORT only, a LONG one both`() = runBlocking {
        fetchedAgo(OPEN_METEO, longH = 5, shortH = 4)
        coordinator().fetchFromAllApis(lat, lon, mapOf(OPEN_METEO to ForecastParts.BOTH), ctx())
        verify { wsm.markShortFetch(OPEN_METEO.id, lat, lon, now) }
        verify(exactly = 0) { wsm.markLongFetch(any(), any(), any(), any()) }

        fetchedAgo(OPEN_METEO, longH = 12, shortH = 4)
        coordinator().fetchFromAllApis(lat, lon, mapOf(OPEN_METEO to ForecastParts.BOTH), ctx())
        verify { wsm.markLongFetch(OPEN_METEO.id, lat, lon, now) }
    }

    @Test
    fun `hourly-only on WeatherAPI (daily refused) makes its one full request but drops the daily rows`() = runBlocking {
        coordinator().fetchFromAllApis(lat, lon, mapOf(WEATHER_API to ForecastParts.HOURLY), ctx())
        coVerify(exactly = 1) { weatherApi.getForecast(lat, lon, any()) }
        coVerify(exactly = 0) { snapshotStore.saveForecastSnapshot(any(), any(), any(), any(), any(), any()) }
        verify { wsm.markShortFetch(WEATHER_API.id, lat, lon, now) }
    }

    @Test
    fun `a daily-only fetch (hours refused) asks Open-Meteo for daily and saves it`() = runBlocking {
        coordinator().fetchFromAllApis(lat, lon, mapOf(OPEN_METEO to ForecastParts.DAILY), ctx())
        coVerify { openMeteo.getForecast(lat, lon, any(), any(), ForecastParts.DAILY) }
        coVerify(exactly = 1) { snapshotStore.saveForecastSnapshot(any(), any(), any(), OPEN_METEO.id, any(), any()) }
    }
}
