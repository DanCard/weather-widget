package com.weatherwidget.ui

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.ui.ForecastHistoryActivity.ButtonMode
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.resolveButtonMode
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.shouldShowTemperatureButton
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.shouldLaunchTemperature
import com.weatherwidget.ui.ForecastHistoryActivity.GraphMode
import com.weatherwidget.testutil.dateEpoch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Integration tests verifying the ForecastHistoryActivity button label logic
 * end-to-end: DB insert → DAO query → decision function.
 *
 * Verifies that today/future dates without actual values switch to hourly mode,
 * while past dates continue to use evolution/error toggles.
 */
@RunWith(AndroidJUnit4::class)
class ForecastHistoryButtonIntegrationTest {

    private lateinit var db: WeatherDatabase
    private val lat = 37.422
    private val lon = -122.084

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Today should route to hourly mode when actual values are unavailable,
     * even if forecast snapshots exist.
     */
    @Test
    fun buttonShowsHourlyForTodayWithoutActuals_evenWhenSnapshotsExist() = runBlocking {
        val today = LocalDate.now().toString()
        val yesterday = LocalDate.now().minusDays(1).toString()

        // Insert a forecast for today (made yesterday)
        db.forecastDao().insertForecast(
            ForecastEntity(
                targetDate = dateEpoch(today),
                forecastDate = dateEpoch(yesterday),
                locationLat = lat,
                locationLon = lon,
                highTemp = 72f,
                lowTemp = 55f,
                condition = "Clear",
                source = "NWS",
                fetchedAt = System.currentTimeMillis(),
            )
        )

        // Query exactly as the activity does
        val snapshots = db.forecastDao().getForecastEvolution(dateEpoch(today), lat, lon)
        assertTrue("Expected forecasts in DB but found none", snapshots.isNotEmpty())

        // Today + no actuals -> temperature button
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

        // Click handler should launch hourly mode
        assertTrue(
            "Click should launch hourly mode when temperature button is active",
            shouldLaunchTemperature(hasDate = true, showTemperatureButton = showTemperatureButton),
        )
    }

    /**
     * Future dates without actual values should also use hourly mode.
     */
    @Test
    fun buttonShowsHourly_whenFutureDateHasNoActuals() = runBlocking {
        val futureDate = LocalDate.now().plusDays(3).toString()

        // Query with no data inserted
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

        // Click handler should launch hourly mode
        assertTrue(
            "Click should launch hourly mode for future date without actual values",
            shouldLaunchTemperature(hasDate = true, showTemperatureButton = showTemperatureButton),
        )
    }

    /**
     * Past dates should keep evolution/error toggle behavior even if actuals are unavailable.
     */
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
                source = "NWS",
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
