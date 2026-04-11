package com.weatherwidget.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.ui.ForecastHistoryActivity.ButtonMode
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.resolveButtonMode
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.shouldShowTemperatureButton
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.shouldLaunchTemperature
import com.weatherwidget.ui.ForecastHistoryActivity.GraphMode
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import com.weatherwidget.data.local.ForecastEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category

@Category(MediumDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForecastHistoryButtonRoboTest {
    private lateinit var db: WeatherDatabase
    private val lat = 37.422
    private val lon = -122.084

    @Before
    fun setUp() {
        db = TestDatabase.create()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun buttonShowsHourlyForTodayWithoutActuals_evenWhenSnapshotsExist() = runBlocking {
        val today = LocalDate.now().toString()
        val yesterday = LocalDate.now().minusDays(1).toString()

        db.forecastDao().insertForecast(
            ForecastEntity(
                targetDate = dateEpoch(today),
                forecastDate = dateEpoch(yesterday),
                locationLat = lat,
                locationLon = lon,
                highTemp = 72f,
                lowTemp = 55f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                fetchedAt = System.currentTimeMillis(),
            )
        )

        val snapshots = db.forecastDao().getForecastEvolution(dateEpoch(today), lat, lon)
        assertTrue("Expected forecasts in DB but found none", snapshots.isNotEmpty())

        val showTemperatureButton = shouldShowTemperatureButton(
            date = LocalDate.parse(today),
            hasActualValues = false,
        )
        val buttonMode = resolveButtonMode(
            showTemperatureButton = showTemperatureButton,
            graphMode = GraphMode.EVOLUTION,
        )
        assertEquals(
            "Button should show Hourly for today without actual values",
            ButtonMode.TEMPERATURE,
            buttonMode,
        )

        assertTrue(
            "Click should launch hourly mode when temperature button is active",
            shouldLaunchTemperature(hasDate = true, showTemperatureButton = showTemperatureButton),
        )
    }

    @Test
    fun buttonShowsHourly_whenFutureDateHasNoActuals() = runBlocking {
        val futureDate = LocalDate.now().plusDays(3).toString()

        val snapshots = db.forecastDao().getForecastEvolution(dateEpoch(futureDate), lat, lon)
        assertTrue("Expected no snapshots for future date", snapshots.isEmpty())

        val showTemperatureButton = shouldShowTemperatureButton(
            date = LocalDate.parse(futureDate),
            hasActualValues = false,
        )
        val buttonMode = resolveButtonMode(
            showTemperatureButton = showTemperatureButton,
            graphMode = GraphMode.EVOLUTION,
        )
        assertEquals(
            "Button should show Hourly when future day has no actual values",
            ButtonMode.TEMPERATURE,
            buttonMode,
        )

        assertTrue(
            "Click should launch hourly mode for future date without actual values",
            shouldLaunchTemperature(hasDate = true, showTemperatureButton = showTemperatureButton),
        )
    }

    @Test
    fun buttonShowsEvolution_whenPastDateAndSnapshotsExist() = runBlocking {
        val targetDate = LocalDate.now().minusDays(2).toString()
        val forecastDate = LocalDate.now().minusDays(3).toString()

        db.forecastDao().insertForecast(
            ForecastEntity(
                targetDate = dateEpoch(targetDate),
                forecastDate = dateEpoch(forecastDate),
                locationLat = lat,
                locationLon = lon,
                highTemp = 68f,
                lowTemp = 52f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                fetchedAt = System.currentTimeMillis(),
            )
        )

        val snapshots = db.forecastDao().getForecastEvolution(dateEpoch(targetDate), lat, lon)
        assertTrue("Expected snapshots for past date", snapshots.isNotEmpty())

        val showTemperatureButton = shouldShowTemperatureButton(
            date = LocalDate.parse(targetDate),
            hasActualValues = false,
        )
        val buttonMode = resolveButtonMode(
            showTemperatureButton = showTemperatureButton,
            graphMode = GraphMode.EVOLUTION,
        )
        assertEquals(
            "Button should stay in evolution mode for past dates",
            ButtonMode.EVOLUTION,
            buttonMode,
        )
        assertFalse(
            "Click should toggle graph mode for past dates",
            shouldLaunchTemperature(hasDate = true, showTemperatureButton = showTemperatureButton),
        )
    }
}