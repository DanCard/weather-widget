package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.SharedPreferencesUtil
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Integration test for Phase C of plans/260821-observations-stale-site-autorefresh.md: the full
 * stale-display repair chain across real components — Hilt-injected [WeatherObservationsActivity]
 * + in-memory Room + `weather_widget_prefs` + the **real** [com.weatherwidget.widget.GpsResampler]
 * (passive-fix boundary; Robolectric resolves it to its no-fix/no-permission outcome, proving the
 * chain survives it) — with only the network call stubbed.
 *
 * The 2026-08-21 Samsung incident this guards against: the Current Observations screen sat on
 * "Fetched 7:17 PM" data for 78 minutes while plugged in, because nothing ever fetched just
 * because what was being looked at was stale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WeatherObservationsStaleAutoRefreshIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: WeatherDatabase

    private val widgetId = 4301
    private val lat = 37.416885
    private val lon = -122.088776
    private var now = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WeatherDatabase.setIsTesting(true)
        database = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(database)

        clearPrefs("weather_widget_prefs")
        clearPrefs("widget_state_prefs")
        WeatherObservationsActivity.autoRefreshDebounceMs = 0L

        now = System.currentTimeMillis()

        val widgetPrefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        widgetPrefs.edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", lat.toFloat())
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", lon.toFloat())
            .commit()
    }

    @After
    fun tearDown() {
        WeatherDatabase.resetInstanceForTesting()
        WeatherDatabase.setIsTesting(false)
        WeatherObservationsActivity.autoRefreshDebounceMs = 500L
        clearPrefs("weather_widget_prefs")
        clearPrefs("widget_state_prefs")
    }

    @Test
    fun `stale displayed rows drive the full chain - resample, refresh, audit row, debounce`() {
        val refreshedLocations = mutableListOf<Pair<Double, Double>>()
        val scenario = launch { activity ->
            activity.forceRefreshDisplayedSource = { latitude, longitude ->
                refreshedLocations.add(latitude to longitude)
            }
        }
        // Seed AFTER launch: onCreate's initial load must see an empty/fresh screen, so the first
        // stale evaluation happens on the flow-driven reload — running through the stubbed
        // boundary, never the production network call.
        seedStaleIncident()
        shadowOf(Looper.getMainLooper()).idle()

        // 1. The network boundary was hit exactly once, under the widget's location.
        assertEquals(1, refreshedLocations.size)
        assertEquals(lat.toDouble(), refreshedLocations[0].first, 1e-4)
        assertEquals(lon.toDouble(), refreshedLocations[0].second, 1e-4)

        runBlocking {
            // 2. The audit trail records the fire with the source the user is viewing.
            val firedRows = database.appLogDao().getLogsByTag("OBS_STALE_AUTO_REFRESH", 10)
                .filter { it.message.contains("outcome=fired") }
            assertEquals(1, firedRows.size)
            assertTrue(firedRows[0].message.startsWith("source=NWS "))

            // 3. The real GpsResampler ran (its own outcome breadcrumb exists) and did not take
            //    the refresh down with it — the passive-fix boundary is best-effort by contract.
            val gpsRows = database.appLogDao().getLogsByTag("GPS_RESAMPLE", 10)
            assertTrue(
                "Expected a GPS_RESAMPLE breadcrumb from the observations_screen trigger, got none",
                gpsRows.any { it.message.contains("trigger=observations_screen") },
            )

            // 4. The debounce window is armed for subsequent loads.
            val prefs = SharedPreferencesUtil.getPrefs(context, "weather_widget_prefs")
            assertTrue(
                prefs.getLong(WeatherObservationsActivity.KEY_LAST_STALE_AUTO_REFRESH_MS, 0L) > 0L,
            )
        }

        // 5. A reload while debounced must not repair again.
        scenario.onActivity { activity ->
            activity.loadObservations()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(1, refreshedLocations.size)
    }

    @Test
    fun `fresh displayed rows leave the chain dormant`() {
        seedFresh()
        val refreshedLocations = mutableListOf<Pair<Double, Double>>()
        launch { activity ->
            activity.forceRefreshDisplayedSource = { latitude, longitude ->
                refreshedLocations.add(latitude to longitude)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(refreshedLocations.isEmpty())
        val logRows = runBlocking { database.appLogDao().getLogsByTag("OBS_STALE_AUTO_REFRESH", 10) }
        assertTrue(logRows.isEmpty())
    }

    /**
     * The incident shape: every station on screen was fetched ~78 minutes ago (fresh rows existed
     * in the DB but under a neighbouring location fragment, so what the screen read was old).
     */
    private fun seedStaleIncident() {
        val staleMs = now - 78 * 60 * 1000L
        runBlocking {
            database.observationDao().insertAll(
                listOf(
                    nwsObservation("AW020", "AE6EO MOUNTAIN VIEW", staleMs, 71.0f),
                    nwsObservation("KNUQ", "Mountain View, Moffett Field", staleMs - 60_000L, 68.0f),
                    nwsObservation("KPAO", "Palo Alto Airport", staleMs - 120_000L, 69.8f),
                ),
            )
        }
    }

    private fun seedFresh() {
        runBlocking {
            database.observationDao().insertAll(
                listOf(
                    nwsObservation("AW020", "AE6EO MOUNTAIN VIEW", now - 60_000L, 71.0f),
                    nwsObservation("KNUQ", "Mountain View, Moffett Field", now - 120_000L, 68.0f),
                ),
            )
        }
    }

    /** Launches the real Hilt-injected activity; only [configure] may stub seams. */
    private fun launch(
        configure: (WeatherObservationsActivity) -> Unit,
    ): ActivityScenario<WeatherObservationsActivity> {
        val intent =
            Intent(context, WeatherObservationsActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
        return ActivityScenario.launch<WeatherObservationsActivity>(intent).also { scenario ->
            scenario.onActivity { activity ->
                // Synchronous loads so the repair chain completes before assertions.
                activity.ioDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()
                configure(activity)
                activity.loadObservations()
                activity.loadFetchLogs()
            }
        }
    }

    private fun nwsObservation(
        stationId: String,
        stationName: String,
        timestamp: Long,
        temperature: Float,
    ): ObservationEntity =
        ObservationEntity(
            stationId = stationId,
            stationName = stationName,
            timestamp = timestamp,
            temperature = temperature,
            condition = "Clear",
            locationLat = lat,
            locationLon = lon,
            distanceKm = 2.5f,
            stationType = "OFFICIAL",
            fetchedAt = timestamp,
            api = "NWS",
        )

    private fun clearPrefs(name: String) {
        context.getSharedPreferences("${name}_test_default", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
