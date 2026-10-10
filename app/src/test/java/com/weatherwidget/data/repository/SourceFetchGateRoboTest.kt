package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.WeatherSource.NWS
import com.weatherwidget.data.model.WeatherSource.OPEN_METEO
import com.weatherwidget.data.model.WeatherSource.SILURIAN
import com.weatherwidget.shared.sourceview.SourceViewKind
import com.weatherwidget.shared.sourceview.SourceViewSwitch
import com.weatherwidget.shared.sourceview.SourceViewTally
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.SourceFetchGateLoader
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import java.time.ZoneId

/**
 * Android's half of "not viewed in 8 days → not fetched in the background": the sync's source
 * selection honours the gate (targeted fetches excepted), and the loader turns `source_view_days`
 * into the gate, failing open. performance/261010-fetch-only-sources-likely-to-be-viewed.md
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class SourceFetchGateRoboTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: WeatherDatabase
    private val lat = 37.42
    private val lon = -122.08

    @Before
    fun setup() {
        WeatherDatabase.setIsTesting(true)
        database = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(database)
        SourceFetchGateLoader.resetLogMemoForTesting()
    }

    @After
    fun teardown() {
        WeatherDatabase.resetInstanceForTesting()
        WeatherDatabase.setIsTesting(false)
    }

    private fun coordinator(): ForecastFetchCoordinator {
        val wsm = mockk<WidgetStateManager>(relaxed = true)
        every { wsm.getVisibleSourcesOrder() } returns listOf(NWS, OPEN_METEO, SILURIAN)
        every { wsm.isSourceVisible(any()) } answers { firstArg<WeatherSource>() in listOf(NWS, OPEN_METEO, SILURIAN) }
        val nwsMapper = mockk<NwsForecastMapper>()
        coEvery { nwsMapper.fetchFromNws(any(), any()) } returns NwsForecastMapper.NwsFetchResult(emptyList(), emptyList())
        return ForecastFetchCoordinator(
            context = context,
            appLogDao = mockk(relaxed = true),
            openMeteoApi = mockk(relaxed = true),
            weatherApi = mockk(relaxed = true),
            silurianApi = mockk(relaxed = true),
            widgetStateManager = wsm,
            tomorrowIoApi = null,
            openWeatherMapApi = null,
            nwsForecastMapper = nwsMapper,
            snapshotStore = mockk(relaxed = true),
            hourlyStore = mockk(relaxed = true),
            weatherApiHistoryBackfiller = mockk(relaxed = true),
            nwsApiDailyActualsFetcher = mockk(relaxed = true),
        )
    }

    @Test
    fun `scheduled and untargeted forced syncs skip gated-out sources, a targeted force does not`() {
        val c = coordinator()
        val gate = setOf(NWS)
        // Empty cache: every source is stale.
        assertEquals(setOf(NWS), c.visibleSourcesToFetch(emptyList(), false, null, null, gate))
        assertEquals("Refresh / location change", setOf(NWS), c.visibleSourcesToFetch(emptyList(), true, null, null, gate))
        assertEquals(
            "a switch to a gated-out source fetches it",
            setOf(NWS, SILURIAN),
            c.visibleSourcesToFetch(emptyList(), true, SILURIAN.id, null, gate),
        )
        assertEquals("gate unavailable fails open", setOf(NWS, OPEN_METEO, SILURIAN), c.visibleSourcesToFetch(emptyList(), false, null, null, null))
    }

    @Test
    fun `requiresNetworkFetch ignores stale gated-out sources`() {
        val c = coordinator()
        assertFalse(c.requiresNetworkFetch(emptyList(), null, emptySet()))
        assertTrue(c.requiresNetworkFetch(emptyList(), null, setOf(OPEN_METEO)))
        assertTrue(c.requiresNetworkFetch(emptyList(), null, null))
    }

    private fun stateManager(): WidgetStateManager = WidgetStateManager(context).apply {
        setVisibleSourcesOrder(listOf(NWS, OPEN_METEO, SILURIAN))
        ActiveLocationResolver.persist(context, lat, lon)
    }

    private fun today() = LocalDate.now(ZoneId.systemDefault())

    private fun track(daysAgo: Long) = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO source_view_tracking (id, startedDate) VALUES (1, ${SourceViewTally.dayMs(today().minusDays(daysAgo))})",
        )
    }

    private fun switchTo(source: WeatherSource, daysAgo: Long) = runBlocking {
        database.sourceViewDao().record(
            SourceViewSwitch(SourceViewTally.dayMs(today().minusDays(daysAgo)), source.id, SourceViewKind.DAILY, SourceViewTrigger.TOGGLE, false),
        )
    }

    @Test
    fun `loader - viewed within 7 days kept, older and never off, primary always`() = runBlocking {
        track(30)
        switchTo(OPEN_METEO, 3)
        switchTo(SILURIAN, 9)
        val gate = SourceFetchGateLoader.load(context, stateManager(), lat, lon)!!
        assertEquals(setOf(NWS, OPEN_METEO), gate.forecasts)
        assertEquals(setOf(SILURIAN), gate.off)
        val logged = database.appLogDao().getLogsByTag(SourceFetchGateLoader.LOG_TAG, 5)
        assertTrue(logged.isNotEmpty())
        assertEquals("on=NWS,OPEN_METEO off=SILURIAN feeds=NWS,OPEN_METEO", logged.first().message)
    }

    @Test
    fun `loader - a switch today brings a gated-out source back`() = runBlocking {
        track(30)
        val wsm = stateManager()
        assertFalse(SILURIAN in SourceFetchGateLoader.load(context, wsm, lat, lon)!!.forecasts)
        switchTo(SILURIAN, 0)
        assertTrue(SILURIAN in SourceFetchGateLoader.load(context, wsm, lat, lon)!!.forecasts)
    }

    @Test
    fun `loader - grace while tracking is under 8 days, and with no tracking row`() = runBlocking {
        val wsm = stateManager()
        assertEquals("no tracking row", setOf(NWS, OPEN_METEO, SILURIAN), SourceFetchGateLoader.load(context, wsm, lat, lon)!!.forecasts)
        track(5)
        assertEquals(setOf(NWS, OPEN_METEO, SILURIAN), SourceFetchGateLoader.load(context, wsm, lat, lon)!!.forecasts)
    }

    @Test
    fun `backgroundSources fails open without a location`() = runBlocking {
        track(30)
        assertEquals(listOf(NWS, OPEN_METEO, SILURIAN), SourceFetchGateLoader.backgroundSources(context, stateManager(), null))
        assertEquals(listOf(NWS), SourceFetchGateLoader.backgroundSources(context, stateManager(), lat to lon))
    }

    @Test
    fun `room table to gate - parity with the shared rule`() = runBlocking {
        track(30)
        switchTo(OPEN_METEO, 7)
        switchTo(SILURIAN, 8)
        val gate = SourceFetchGateLoader.load(context, stateManager(), lat, lon)!!
        assertEquals("7 days on, 8 days off", setOf(NWS, OPEN_METEO), gate.forecasts)
    }
}
