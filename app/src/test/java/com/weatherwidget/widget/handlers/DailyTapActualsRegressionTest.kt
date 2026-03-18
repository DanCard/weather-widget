package com.weatherwidget.widget.handlers

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.ZoomLevel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Regression test for: tapping "today" from daily forecast view showed no actual temperature line.
 *
 * Root cause: WeatherWidgetProvider.handleDayClickAction called
 *   handleSetView(context, appWidgetId, targetMode, targetOffset)
 * without passing `repository`, so TemperatureViewHandler received repository=null
 * and skipped the getHourlyActuals() call entirely (returned emptyList()).
 *
 * Fix: pass repository as the fifth argument.
 *
 * These tests verify the data-flow contract:
 *  - repository=null  → actuals=emptyList() → all isActual=false (old broken behaviour)
 *  - repository≠null  → actuals from DB     → isActual=true for matching hours (correct)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyTapActualsRegressionTest {

    private lateinit var context: Context
    private lateinit var db: com.weatherwidget.data.local.WeatherDatabase
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")

    // Fixed center so tests are deterministic regardless of wall-clock time
    private val center = LocalDateTime.of(2026, 3, 16, 13, 0)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Forecast entities covering the WIDE window around [center]. */
    private fun wideForecasts(): List<com.weatherwidget.data.local.HourlyForecastEntity> {
        val start = center.minusHours(10)
        val end = center.plusHours(50)
        val result = mutableListOf<com.weatherwidget.data.local.HourlyForecastEntity>()
        var cur = start
        while (!cur.isAfter(end)) {
            result.add(TestData.hourly(dateTime = cur.format(fmt), temperature = 60f + cur.hour))
            cur = cur.plusHours(1)
        }
        return result
    }

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

    /**
     * Simulates the broken path: repository was null so actuals were never queried.
     * buildHourDataList receives emptyList() and produces no isActual=true hours.
     */
    @Test
    fun `repository null — actuals not queried — no isActual hours — broken path`() {
        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = wideForecasts(),
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = emptyList(), // what repository=null produces
        )

        assertTrue("Hours should be present", hours.isNotEmpty())
        assertEquals(
            "repository=null path: zero isActual hours (the bug)",
            0,
            hours.count { it.isActual },
        )
    }

    /**
     * Simulates the fixed path: repository is non-null, actuals are queried from the DB,
     * and buildHourDataList receives them so isActual=true for past hours.
     */
    @Test
    fun `repository non-null — actuals queried from DB — isActual hours present — fixed path`() = runTest {
        val dao = db.observationDao()

        // Insert actuals for the past portion of the WIDE window (hours 5a–12p)
        val pastHours = listOf("05", "06", "07", "08", "09", "10", "11", "12")
        dao.insertAll(pastHours.map { h ->
            TestData.observation(timestamp = java.time.LocalDateTime.parse("2026-03-16T$h:00").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 62f)
        })

        // Fetch actuals the same way TemperatureViewHandler does when repository≠null
        val graphStart = center.minusHours(ZoomLevel.WIDE.backHours)
        val graphEnd = center.plusHours(ZoomLevel.WIDE.forwardHours)
        val minEpoch = graphStart.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val maxEpoch = graphEnd.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val actuals = dao.getObservationsInRange(
            startTs = minEpoch,
            endTs = maxEpoch,
            lat = TestData.LAT,
            lon = TestData.LON,
        )

        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = wideForecasts(),
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = actuals,
        )

        val actualCount = hours.count { it.isActual }
        assertTrue(
            "Fixed path: should have isActual=true hours (got $actualCount from ${hours.size} total)",
            actualCount > 0,
        )
        // Verify each isActual hour has a non-null actualTemperature
        hours.filter { it.isActual }.forEach { h ->
            assertTrue(
                "Hour ${h.dateTime} has isActual=true but null actualTemperature",
                h.actualTemperature != null,
            )
        }
    }

    /**
     * Confirms that the fixed and broken paths produce different results with the same DB data.
     * This is the sharpest expression of the regression.
     */
    @Test
    fun `repository null vs non-null produce different isActual counts for same DB data`() = runTest {
        val dao = db.observationDao()
        dao.insertAll(listOf(
            TestData.observation(timestamp = java.time.LocalDateTime.parse("2026-03-16T10:00").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 60f),
            TestData.observation(timestamp = java.time.LocalDateTime.parse("2026-03-16T11:00").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 61f),
            TestData.observation(timestamp = java.time.LocalDateTime.parse("2026-03-16T12:00").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 62f),
        ))

        val forecasts = wideForecasts()

        // Broken path — repository was null, actuals never fetched
        val brokenHours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = emptyList(),
        )

        // Fixed path — repository is non-null, actuals fetched from DB
        val graphStart = center.minusHours(ZoomLevel.WIDE.backHours)
        val graphEnd = center.plusHours(ZoomLevel.WIDE.forwardHours)
        val minEpoch = graphStart.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val maxEpoch = graphEnd.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val actuals = dao.getObservationsInRange(minEpoch, maxEpoch, TestData.LAT, TestData.LON)
        val fixedHours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = actuals,
        )

        assertEquals("Broken path: 0 isActual hours", 0, brokenHours.count { it.isActual })
        assertTrue("Fixed path should have isActual hours", fixedHours.count { it.isActual } >= 3)
    }
}
