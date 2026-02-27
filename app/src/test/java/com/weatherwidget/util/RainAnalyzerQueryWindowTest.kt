package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestData.LAT
import com.weatherwidget.testutil.TestData.LON
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WeatherWidgetProvider.Companion.HOURLY_LOOKAHEAD_HOURS
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Regression test for the rain analysis query window bug:
 * The standard 60h lookahead must cover rain data 2 days out.
 * A narrow ±3h window (the old WidgetIntentRouter bug) misses it.
 */
@RunWith(RobolectricTestRunner::class)
class RainAnalyzerQueryWindowTest {
    private lateinit var db: WeatherDatabase
    private lateinit var hourlyDao: HourlyForecastDao

    @Before
    fun setup() {
        db = TestDatabase.create()
        hourlyDao = db.hourlyForecastDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `60h lookahead covers rain 2 days out`() = runTest {
        val now = LocalDateTime.now()
        val twoDaysOut = now.plusDays(2)
        val rainDateTime = twoDaysOut.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))

        // Insert rain data 2 days from now at the same time as now
        hourlyDao.insertAll(
            listOf(TestData.hourly(dateTime = rainDateTime, condition = "Rain", precipProbability = 80)),
        )

        // Query with the standard 60h window
        val hourlyStart = now.minusHours(24).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        val hourlyEnd = now.plusHours(HOURLY_LOOKAHEAD_HOURS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        val wideResult = hourlyDao.getHourlyForecasts(hourlyStart, hourlyEnd, LAT, LON)

        assertTrue("60h window should include rain 2 days out", wideResult.any { it.precipProbability == 80 })

        // Analyze rain for that day
        val rainDate = twoDaysOut.toLocalDate()
        val analysis = RainAnalyzer.analyzeDay(wideResult, rainDate, now = now)
        assertNotNull("Should detect rain 2 days out", analysis)
        assertTrue("Should report rain", analysis!!.hasRain)
    }

    @Test
    fun `narrow 3h window misses rain 2 days out`() = runTest {
        val now = LocalDateTime.now()
        val twoDaysOut = now.plusDays(2)
        val rainDateTime = twoDaysOut.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))

        hourlyDao.insertAll(
            listOf(TestData.hourly(dateTime = rainDateTime, condition = "Rain", precipProbability = 80)),
        )

        // Query with a narrow ±3h window (the old bug)
        val narrowStart = now.minusHours(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        val narrowEnd = now.plusHours(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
        val narrowResult = hourlyDao.getHourlyForecasts(narrowStart, narrowEnd, LAT, LON)

        assertTrue("Narrow window should miss rain 2 days out", narrowResult.none { it.precipProbability == 80 })
    }
}
