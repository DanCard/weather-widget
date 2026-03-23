package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestData.LAT
import com.weatherwidget.testutil.TestData.LON
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.TemperatureInterpolator
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
class ForecastRoundingTest {
    private lateinit var db: WeatherDatabase
    private lateinit var repository: ForecastRepository

    private val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Before
    fun setup() {
        db = TestDatabase.create()
        val context = RuntimeEnvironment.getApplication()
        repository = ForecastRepository(context, db.forecastDao(), db.hourlyForecastDao(), db.appLogDao(), mockk(), mockk(), mockk(), mockk(relaxed = true), mockk(relaxed = true), db.climateNormalDao(), db.observationDao(), mockk(relaxed = true), mockk(relaxed = true))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `today forecast preserves decimal precision while tomorrow is rounded`() = runTest {
        val weather = listOf(
            TestData.forecast(targetDate = today, source = "OPEN_METEO", highTemp = 72.4f, lowTemp = 50.6f),
            TestData.forecast(targetDate = tomorrow, source = "OPEN_METEO", highTemp = 72.4f, lowTemp = 50.6f)
        )
        
        repository.saveForecastSnapshot(weather, LAT, LON, "OPEN_METEO")
        
        val savedForecasts = db.forecastDao().getForecastsInRange(dateEpoch(today), dateEpoch(tomorrow), LAT, LON)
            .filter { it.source == "OPEN_METEO" }
            .sortedBy { it.targetDate }

        assertEquals(2, savedForecasts.size)

        // Verify today's precision
        val todaySaved = savedForecasts.find { it.targetDate == dateEpoch(today) }!!
        assertEquals(72.4f, todaySaved.highTemp!!, 0.001f)
        assertEquals(50.6f, todaySaved.lowTemp!!, 0.001f)

        // Verify tomorrow's rounding
        val tomorrowSaved = savedForecasts.find { it.targetDate == dateEpoch(tomorrow) }!!
        assertEquals(72.0f, tomorrowSaved.highTemp!!, 0.001f)
        assertEquals(51.0f, tomorrowSaved.lowTemp!!, 0.001f)
    }
}
