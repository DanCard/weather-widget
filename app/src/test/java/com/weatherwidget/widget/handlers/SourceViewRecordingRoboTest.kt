package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.SourceViewDayEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.sourceview.SourceViewKind
import com.weatherwidget.shared.sourceview.SourceViewProbability
import com.weatherwidget.shared.sourceview.SourceViewSwitch
import com.weatherwidget.shared.sourceview.SourceViewTally
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The widget's API and home buttons count switches into `source_view_days`, and what Room stores
 * reads back through the shared estimator with the same numbers as the pure test
 * (SourceViewProbabilityTest). plans/261010-source-view-tracking-table.md
 */
@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourceViewRecordingRoboTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private lateinit var database: WeatherDatabase
    private val widgetId = 77

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WeatherDatabase.setIsTesting(true)
        database = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(database)
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
        stateManager.setViewMode(widgetId, ViewMode.DAILY)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))
        ActiveLocationResolver.persist(context, 37.4220, -122.0841)
        RefreshScheduler.setIsRefreshDisabledForTesting(true)
        org.robolectric.shadows.ShadowLog.stream = System.out
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(widgetId)
        RefreshScheduler.setIsRefreshDisabledForTesting(false)
        WeatherDatabase.resetInstanceForTesting()
        WeatherDatabase.setIsTesting(false)
    }

    private fun rows() = runBlocking { database.sourceViewDao().getSince(0L) }
        .associateBy { Triple(it.sourceId, it.triggerKind, it.viewKind) }

    @Test
    fun `api button counts each switch with its view and primary flag`() = runBlocking {
        repeat(4) { WidgetIntentRouter.handleToggleApi(context, widgetId) } // METEO, WAPI, NWS, METEO

        val r = rows()
        assertEquals(3, r.size)
        val meteo = r.getValue(Triple("OPEN_METEO", "TOGGLE", "DAILY"))
        assertEquals(2, meteo.switches)
        assertEquals(false, meteo.wasPrimary)
        assertEquals(1, r.getValue(Triple("WEATHER_API", "TOGGLE", "DAILY")).switches)
        assertTrue(r.getValue(Triple("NWS", "TOGGLE", "DAILY")).wasPrimary)
        val today = SourceViewTally.dayMs(System.currentTimeMillis(), ZoneId.systemDefault())
        assertTrue(r.values.all { it.date == today })
    }

    @Test
    fun `api button in an hourly view counts as HOURLY`() = runBlocking {
        stateManager.setViewMode(widgetId, ViewMode.TEMPERATURE)
        WidgetIntentRouter.handleToggleApi(context, widgetId)
        assertEquals(1, rows().getValue(Triple("OPEN_METEO", "TOGGLE", "HOURLY")).switches)
    }

    @Test
    fun `home button counts HOME, a stale home intent counts nothing`() = runBlocking {
        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.OPEN_METEO)
        WidgetIntentRouter.handleResetSource(context, widgetId)
        WidgetIntentRouter.handleResetSource(context, widgetId) // already home: stale PendingIntent

        val r = rows()
        assertEquals(1, r.size)
        val home = r.getValue(Triple("NWS", "HOME", "DAILY"))
        assertEquals(1, home.switches)
        assertTrue(home.wasPrimary)
    }

    @Test
    fun `room rows read back through the shared estimator - parity with the pure test`() = runBlocking {
        val dao = database.sourceViewDao()
        val today = LocalDate.of(2026, 10, 10)
        fun switch(daysAgo: Long, source: String = "OPEN_METEO") = SourceViewSwitch(
            SourceViewTally.dayMs(today.minusDays(daysAgo)), source, SourceViewKind.DAILY, SourceViewTrigger.TOGGLE, false,
        )
        dao.record(switch(3))
        dao.record(switch(3)) // same row: increments, still one day
        dao.record(switch(45)) // beyond the lookback

        val loaded = dao.getSince(0L).map { it.toRow() }
        assertEquals(2, loaded.size)
        val since = today.minusDays(60)
        assertEquals(0.157, SourceViewProbability.toggleToday(loaded, since, today).probability, 0.001)
        assertEquals(
            0.157,
            SourceViewProbability.sourceViewedToday("OPEN_METEO", loaded, since, today, "NWS").probability,
            0.001,
        )
    }

    @Test
    fun `retention drops rows older than the day-aligned 30-day cutoff`() = runBlocking {
        val dao = database.sourceViewDao()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val todayMs = SourceViewTally.dayMs(now, zone)
        listOf(0L, 30L, 31L, 60L).forEach { age ->
            dao.insert(SourceViewDayEntity(todayMs - age * SourceViewTally.DAY_MS, "OPEN_METEO", "DAILY", "TOGGLE", false, 1))
        }
        dao.deleteOlderThan(SourceViewTally.retentionCutoffMs(now, zone))
        assertEquals(
            listOf(todayMs - 30 * SourceViewTally.DAY_MS, todayMs),
            dao.getSince(0L).map { it.date }.sorted(),
        )
    }
}
