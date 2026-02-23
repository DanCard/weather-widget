package com.weatherwidget.ui

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.data.local.ForecastSnapshotEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.ui.ForecastHistoryActivity.ButtonMode
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.resolveButtonMode
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.shouldLaunchHourly
import com.weatherwidget.ui.ForecastHistoryActivity.GraphMode
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
 * Guards against the regression where the button showed "Hourly Temperature Forecast"
 * even when forecast history snapshots existed (e.g., for today with collected snapshots).
 * The original bug used date-based logic (isPastDate) instead of data-based logic
 * (hasSnapshots) to decide the button label.
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
     * When forecast snapshots exist for a date, the button should show "Evolution"
     * (not "Hourly Temperature Forecast"). This is the core regression case:
     * today has snapshots, so the user should see the Evolution/Error toggle.
     */
    @Test
    fun buttonShowsEvolution_whenSnapshotsExist() = runBlocking {
        val today = LocalDate.now().toString()
        val yesterday = LocalDate.now().minusDays(1).toString()

        // Insert a forecast snapshot for today (made yesterday)
        db.forecastSnapshotDao().insertSnapshot(
            ForecastSnapshotEntity(
                targetDate = today,
                forecastDate = yesterday,
                locationLat = lat,
                locationLon = lon,
                highTemp = 72,
                lowTemp = 55,
                condition = "Clear",
                source = "NWS",
                fetchedAt = System.currentTimeMillis(),
            )
        )

        // Query exactly as the activity does
        val snapshots = db.forecastSnapshotDao().getForecastEvolution(today, lat, lon)
        assertTrue("Expected snapshots in DB but found none", snapshots.isNotEmpty())

        // Verify: button should show Evolution, NOT "Hourly Temperature Forecast"
        val buttonMode = resolveButtonMode(
            snapshotsEmpty = snapshots.isEmpty(),
            graphMode = GraphMode.EVOLUTION,
        )
        assertEquals(
            "Button should show Evolution when snapshots exist (was the regression bug)",
            ButtonMode.EVOLUTION,
            buttonMode,
        )

        // Click handler should toggle graph mode, not launch hourly
        assertFalse(
            "Click should toggle graph mode when snapshots exist",
            shouldLaunchHourly(hasDate = true, snapshotsEmpty = snapshots.isEmpty()),
        )
    }

    /**
     * When no forecast snapshots exist for a date, the button should show
     * "Hourly Temperature Forecast" and clicking it should launch hourly mode.
     */
    @Test
    fun buttonShowsHourly_whenNoSnapshotsExist() = runBlocking {
        val futureDate = LocalDate.now().plusDays(3).toString()

        // Query with no data inserted
        val snapshots = db.forecastSnapshotDao().getForecastEvolution(futureDate, lat, lon)
        assertTrue("Expected no snapshots for future date", snapshots.isEmpty())

        // Verify: button should show Hourly
        val buttonMode = resolveButtonMode(
            snapshotsEmpty = snapshots.isEmpty(),
            graphMode = GraphMode.EVOLUTION,
        )
        assertEquals(
            "Button should show Hourly when no snapshots exist",
            ButtonMode.HOURLY,
            buttonMode,
        )

        // Click handler should launch hourly mode
        assertTrue(
            "Click should launch hourly mode when no snapshots exist",
            shouldLaunchHourly(hasDate = true, snapshotsEmpty = snapshots.isEmpty()),
        )
    }
}
