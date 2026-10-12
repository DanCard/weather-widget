package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.WeatherSource.NWS
import com.weatherwidget.data.model.WeatherSource.OPEN_METEO
import com.weatherwidget.data.model.WeatherSource.WEATHER_API
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.SourceQuotaBlocks
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.shared.sourceview.SourceLastView
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.ForecastFetchContext
import com.weatherwidget.widget.SourceFetchGateLoader
import com.weatherwidget.widget.SourceViewRecorder
import com.weatherwidget.widget.ViewMode
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
 * Android's hourly cadence: the sync plans daily and hourly separately per source, fetches only the
 * parts due, and reads view age from `source_last_view`.
 * performance/261011-hourly-cadence-by-last-view-and-fuzzy-full-window.md
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class HourlyCadencePlanRoboTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: WeatherDatabase
    private val lat = 37.42
    private val lon = -122.08
    private val hour = 3_600_000L
    private val now = System.currentTimeMillis()

    private val lastHourly = mutableMapOf<String, Long?>()
    private val lastFull = mutableMapOf<String, Long?>()
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
        every { wsm.getLastHourlyFetch(any(), any(), any()) } answers { lastHourly[firstArg()] }
        every { wsm.getLastFullHourlyFetch(any(), any(), any()) } answers { lastFull[firstArg()] }
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

    private fun ctx(charging: Boolean = true, battery: Int = 90, hourlyLimited: Boolean = false) =
        ForecastFetchContext(charging, isScreenInteractive = true, batteryLevel = battery, activeSourceIds = setOf(NWS.id), hourlyLimited = hourlyLimited)

    /** A daily row for [source] fetched [ageHours] ago. */
    private fun daily(source: WeatherSource, ageHours: Long) = ForecastEntity(
        targetDate = 0L, dateOfPrediction = 0L, locationLat = lat, locationLon = lon, highTemp = 70f, lowTemp = 50f,
        condition = "Sunny", source = source.id, batchFetchedAt = now - ageHours * hour, fetchedAt = now - ageHours * hour,
    )

    private val freshDaily = listOf(daily(NWS, 1), daily(OPEN_METEO, 1), daily(WEATHER_API, 1))

    private fun recency(vararg views: Pair<WeatherSource, Long>) =
        SourceLastView.Recency(views.associate { (s, ageHours) -> s.id to now - ageHours * hour }, inGrace = false)

    private fun plan(cached: List<ForecastEntity>, recency: SourceLastView.Recency, ctx: ForecastFetchContext = ctx(), force: Boolean = false) =
        coordinator().planFetch(cached, force, null, ctx, null, recency, lat, lon)

    @Test
    fun `daily fresh and displayed hourly due on the charger is hourly-only`() {
        lastHourly[NWS.id] = now - 5 * hour
        lastFull[NWS.id] = now - hour
        assertEquals(mapOf(NWS to ForecastParts.HOURLY), plan(freshDaily, recency()))
    }

    @Test
    fun `daily stale and hourly fresh is daily-only`() {
        lastHourly[NWS.id] = now - hour
        lastHourly[OPEN_METEO.id] = now - hour
        lastHourly[WEATHER_API.id] = now - hour
        val cached = listOf(daily(NWS, 10), daily(OPEN_METEO, 1), daily(WEATHER_API, 1))
        assertEquals(mapOf(NWS to ForecastParts.DAILY), plan(cached, recency()))
    }

    @Test
    fun `an other source viewed 30 h ago refetches hourly after 6 h on the charger, never-viewed ones never`() {
        lastHourly[NWS.id] = now - hour
        lastHourly[OPEN_METEO.id] = now - 7 * hour
        lastFull[OPEN_METEO.id] = now - hour
        lastHourly[WEATHER_API.id] = now - 100 * hour
        assertEquals(mapOf(OPEN_METEO to ForecastParts.HOURLY), plan(freshDaily, recency(OPEN_METEO to 30)))
        lastHourly[OPEN_METEO.id] = now - 5 * hour
        assertEquals(emptyMap<WeatherSource, ForecastParts>(), plan(freshDaily, recency(OPEN_METEO to 30)))
    }

    @Test
    fun `on battery at 50 percent only the displayed source gets hourly, once a day`() {
        lastHourly[NWS.id] = now - 25 * hour
        lastHourly[OPEN_METEO.id] = null
        val c = ctx(charging = false, battery = 50)
        assertEquals(mapOf(NWS to ForecastParts.HOURLY), plan(freshDaily, recency(OPEN_METEO to 1), c))
    }

    @Test
    fun `hourly due inside a FULL window takes the daily rows too`() {
        lastHourly[NWS.id] = now - 5 * hour
        lastFull[NWS.id] = now - 21 * hour
        assertEquals(mapOf(NWS to ForecastParts.BOTH), plan(freshDaily, recency()))
    }

    /** performance/261012-forecast-loop-quota-blocked-part-is-not-due.md */
    @Test
    fun `a quota-blocked hourly part is not planned and does not drag daily along, other sources unaffected`() {
        lastHourly[NWS.id] = now - 5 * hour
        lastFull[NWS.id] = now - 21 * hour
        lastHourly[OPEN_METEO.id] = now - 7 * hour
        lastFull[OPEN_METEO.id] = now - hour
        SourceQuotaBlocks.block(NWS.id, ForecastProduct.HOURLY, now + 3 * hour)
        assertEquals(mapOf(OPEN_METEO to ForecastParts.HOURLY), plan(freshDaily, recency(OPEN_METEO to 30)))
        lastHourly[OPEN_METEO.id] = now - hour
        assertFalse(coordinator().requiresNetworkFetch(freshDaily, ctx(), null, recency(OPEN_METEO to 30), lat, lon))
    }

    @Test
    fun `an hourly-limited force takes hourly only when due`() {
        lastHourly[NWS.id] = now - hour
        lastHourly[OPEN_METEO.id] = now - hour
        lastHourly[WEATHER_API.id] = now - hour
        val limited = plan(freshDaily, recency(OPEN_METEO to 1, WEATHER_API to 1), ctx(hourlyLimited = true), force = true)
        assertEquals(setOf(ForecastParts.DAILY), limited.values.toSet())
        val full = plan(freshDaily, recency(), ctx(), force = true)
        assertEquals(setOf(ForecastParts.BOTH), full.values.toSet())
    }

    @Test
    fun `requiresNetworkFetch wakes for hourly alone`() {
        lastHourly[NWS.id] = now - 5 * hour
        assertTrue(coordinator().requiresNetworkFetch(freshDaily, ctx(), null, recency(), lat, lon))
        lastHourly[NWS.id] = now - hour
        assertFalse(coordinator().requiresNetworkFetch(freshDaily, ctx(), null, recency(), lat, lon))
    }

    @Test
    fun `hourly-only fetch asks Open-Meteo for hours, saves no daily rows and marks the hourly fetch`() = runBlocking {
        coordinator().fetchFromAllApis(lat, lon, mapOf(OPEN_METEO to ForecastParts.HOURLY), ctx())
        coVerify { openMeteo.getForecast(lat, lon, any(), any(), ForecastParts.HOURLY) }
        coVerify(exactly = 0) { snapshotStore.saveForecastSnapshot(any(), any(), any(), any(), any(), any()) }
        verify { wsm.markHourlyFetch(OPEN_METEO.id, lat, lon, now) }
    }

    @Test
    fun `daily-only fetch asks Open-Meteo for daily, saves it and leaves the hourly cadence alone`() = runBlocking {
        coordinator().fetchFromAllApis(lat, lon, mapOf(OPEN_METEO to ForecastParts.DAILY), ctx())
        coVerify { openMeteo.getForecast(lat, lon, any(), any(), ForecastParts.DAILY) }
        coVerify(exactly = 1) { snapshotStore.saveForecastSnapshot(any(), any(), any(), OPEN_METEO.id, any(), any()) }
        verify(exactly = 0) { wsm.markHourlyFetch(any(), any(), any(), any()) }
    }

    @Test
    fun `hourly-only on WeatherAPI makes its one full request but drops the daily rows`() = runBlocking {
        coordinator().fetchFromAllApis(lat, lon, mapOf(WEATHER_API to ForecastParts.HOURLY), ctx())
        coVerify(exactly = 1) { weatherApi.getForecast(lat, lon, any()) }
        coVerify(exactly = 0) { snapshotStore.saveForecastSnapshot(any(), any(), any(), any(), any(), any()) }
        verify { wsm.markHourlyFetch(WEATHER_API.id, lat, lon, now) }
    }

    @Test
    fun `a recorded switch becomes the source's last view`() = runBlocking {
        val at = now - 2 * hour
        SourceViewRecorder.record(context, OPEN_METEO, ViewMode.DAILY, SourceViewTrigger.TOGGLE, nowMs = at)
        val recency = SourceFetchGateLoader.loadRecency(context, listOf(OPEN_METEO.id, NWS.id), nowMs = now)
        assertEquals(at, recency.of(OPEN_METEO.id))
        assertEquals(null, recency.of(NWS.id))
    }
}
