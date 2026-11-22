package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.shared.util.HeaderFit
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
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

/**
 * [HeaderFitPersistLog] against a real Room `app_logs` table: renders that repeat a header's
 * buttons write nothing, a buttons-hidden change writes one INFO row that explains it, and the
 * latest decision stays available for the bug report even when no row was written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class HeaderFitPersistLogIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: WeatherDatabase

    private val wide = HeaderFit.Measured(width = 1150f, rightCluster = 150f, gap = 12f, temp = 120f, baseButtons = 3, buttonWidth = 100f)
    private val narrow = wide.copy(width = 420f)
    private val shown = HeaderFit.Plan(showTemp = true, buttons = 3, placement = HeaderFit.ButtonPlacement.SHOWN, buttonZone = 100f)
    private val hidden = HeaderFit.Plan(showTemp = true, placement = HeaderFit.ButtonPlacement.HIDDEN)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WeatherDatabase.setIsTesting(true)
        database = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(database)
    }

    @After
    fun tearDown() {
        database.close()
        WeatherDatabase.setIsTesting(false)
        WeatherDatabase.resetInstanceForTesting()
    }

    private fun headerRows(expected: Int): List<AppLogEntity> = runBlocking {
        // record() persists on a background scope; wait for the expected rows to land.
        var rows = emptyList<AppLogEntity>()
        repeat(100) {
            rows = database.appLogDao().getLogsByTag(HeaderFit.LOG_TAG, 50)
            if (rows.size >= expected) return@runBlocking rows
            Thread.sleep(20)
        }
        rows
    }

    @Test
    fun onlyButtonChangesPersistAndLatestDecisionIsAlwaysKept() {
        val widgetId = 9101
        HeaderFitPersistLog.record(context, widgetId, "daily", 384f, 1f, shown, wide)
        HeaderFitPersistLog.record(context, widgetId, "daily", 384f, 1f, shown, wide)
        HeaderFitPersistLog.record(context, widgetId, "daily", 384f, 1f, shown, wide)
        val afterRepeats = headerRows(1)
        println("HeaderFitPersistLogIntegrationTest after repeats: ${afterRepeats.map { it.message }}")
        assertEquals("first render persists once; repeats do not", 1, afterRepeats.size)

        HeaderFitPersistLog.record(context, widgetId, "daily", 140f, 1f, hidden, narrow)
        val afterHide = headerRows(2)
        println("HeaderFitPersistLogIntegrationTest after hide: ${afterHide.map { it.message }}")
        assertEquals("the buttons-hidden change persists", 2, afterHide.size)
        val hideRow = afterHide.maxBy { it.id }
        assertEquals("INFO", hideRow.level)
        assertTrue("row names the widget", hideRow.message.startsWith("widget=$widgetId view=daily widthDp=140"))
        assertTrue("row says buttons are hidden", hideRow.message.contains("placement=HIDDEN"))
        assertTrue("row carries the measured widths that explain it", hideRow.message.contains("width=420.0"))

        assertEquals(
            "latest decision is available to the bug report",
            listOf("daily"),
            HeaderFitPersistLog.latestLines(widgetId).keys.toList(),
        )
        assertTrue(HeaderFitPersistLog.latestLines(widgetId).getValue("daily").contains("placement=HIDDEN"))
    }

    @Test
    fun eachViewOfAWidgetIsTrackedSeparately() {
        val widgetId = 9102
        HeaderFitPersistLog.record(context, widgetId, "daily", 384f, 1f, shown, wide)
        HeaderFitPersistLog.record(context, widgetId, "cloud", 384f, 1f, shown, wide)
        val rows = headerRows(2)
        println("HeaderFitPersistLogIntegrationTest per-view rows: ${rows.map { it.message }}")
        assertEquals(2, rows.size)
        assertEquals(listOf("cloud", "daily"), HeaderFitPersistLog.latestLines(widgetId).keys.toList())
    }
}
