package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.TextView
import androidx.core.view.get
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherObservationsActivityRobolectricTest {
    private lateinit var context: Context
    private lateinit var database: WeatherDatabase
    private lateinit var stateManager: WidgetStateManager

    private val widgetId = 4201
    private val lat = 37.416885
    private val lon = -122.088776
    private var now = 0L
    private val dbName = "weather_observations_activity_test_db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WeatherDatabase.setIsTesting(true)
        WeatherDatabase.setDatabaseNameOverrideForTesting(dbName)
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
        clearTestPrefs("weather_widget_prefs")
        clearTestPrefs("widget_state_prefs")
        clearTestPrefs("weather_prefs")

        now = System.currentTimeMillis()

        database = WeatherDatabase.getDatabase(context)
        stateManager = WidgetStateManager(context, database.appLogDao())

        val widgetPrefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        widgetPrefs.edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", lat.toFloat())
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", lon.toFloat())
            .commit()

        runBlocking {
            database.observationDao().insertAll(
                listOf(
                    observation("SILURIAN_MAIN", "Silurian: Current", now, 67.7f, 0f),
                    observation("SILURIAN_1", "Silurian: North", now - 1_000L, 68.2f, 0f),
                    observation("AW020", "AE6EO MOUNTAIN VIEW", now - 10_000L, 73.0f, 2.9f, stationType = "PERSONAL"),
                    observation("KNUQ", "Mountain View, Moffett Field", now - 20_000L, 68.0f, 3.7f, stationType = "OFFICIAL"),
                    observation("WEATHER_API_MAIN", "WAPI: Current", now - 30_000L, 68.5f, 0f),
                ),
            )
            database.appLogDao().insert(
                AppLogEntity(
                    timestamp = now,
                    tag = "CURR_FETCH_START",
                    message = "reason=opportunistic_job targets=NWS, SILURIAN, WEATHER_API",
                ),
            )
            database.appLogDao().insert(
                AppLogEntity(
                    timestamp = now + 1_000L,
                    tag = "CURR_FETCH_DONE",
                    message = "reason=opportunistic_job updated=3",
                ),
            )
            database.appLogDao().insert(
                AppLogEntity(
                    timestamp = now + 2_000L,
                    tag = "CURR_FETCH_ERROR",
                    message = "source=SILURIAN error=timeout",
                    level = "WARN",
                ),
            )
        }
    }

    @After
    fun tearDown() {
        WeatherDatabase.resetInstanceForTesting()
        WeatherDatabase.setDatabaseNameOverrideForTesting(null)
        WeatherDatabase.setIsTesting(false)
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
        clearTestPrefs("weather_widget_prefs")
        clearTestPrefs("widget_state_prefs")
        clearTestPrefs("weather_prefs")
        context.deleteDatabase(dbName)
    }

    @Test
    fun `nws mode excludes silurian rows and shows current fetch logs`() {
        val scenario = launchActivity()

        scenario.onActivity { activity ->
            val adapter = activity.findViewById<RecyclerView>(R.id.observations_list).adapter as WeatherObservationsActivity.ObservationAdapter
            val stationIds = adapter.items.map { it.stationId }
            val subtitle = activity.findViewById<TextView>(R.id.subtitle).text.toString()
            val logs = activity.findViewById<TextView>(R.id.fetch_logs).text.toString()
            val firstRow =
                activity.findViewById<RecyclerView>(R.id.observations_list)[0]
            val stationTypeLabel =
                firstRow.findViewById<TextView>(R.id.station_type_badge).text.toString()

            assertEquals(listOf("AW020", "KNUQ"), stationIds)
            assertEquals("Real-time data from nearby stations", subtitle)
            assertEquals("Station type: PERSONAL", stationTypeLabel)
            assertFalse(logs.contains("No recent fetch logs for NWS"))
            assertTrue(logs.contains("start reason=opportunistic_job targets=NWS, SILURIAN, WEATHER_API"))
            assertTrue(logs.contains("done reason=opportunistic_job updated=3"))
        }
    }

    @Test
    fun `cycling to silurian shows only silurian observations and source error logs`() {
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.SILURIAN, WeatherSource.WEATHER_API))
        val scenario = launchActivity()

        scenario.onActivity { activity ->
            activity.findViewById<TextView>(R.id.api_source_button).performClick()
        }

        waitForUiWork()

        scenario.onActivity { activity ->
            val adapter = activity.findViewById<RecyclerView>(R.id.observations_list).adapter as WeatherObservationsActivity.ObservationAdapter
            val stationIds = adapter.items.map { it.stationId }
            val subtitle = activity.findViewById<TextView>(R.id.subtitle).text.toString()
            val logs = activity.findViewById<TextView>(R.id.fetch_logs).text.toString()
            val sourceButton = activity.findViewById<TextView>(R.id.api_source_button).text.toString()
            val firstRow =
                activity.findViewById<RecyclerView>(R.id.observations_list)[0]
            val stationTypeLabel =
                firstRow.findViewById<TextView>(R.id.station_type_badge).text.toString()

            assertEquals("Silur", sourceButton)
            assertEquals(listOf("SILURIAN_MAIN", "SILURIAN_1"), stationIds)
            assertEquals("Latest reading from Silurian", subtitle)
            assertEquals("Station type: UNKNOWN", stationTypeLabel)
            assertTrue(logs.contains("error error=timeout"))
        }
    }

    private fun launchActivity(): ActivityScenario<WeatherObservationsActivity> {
        val intent =
            Intent(context, WeatherObservationsActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
        val scenario = ActivityScenario.launch<WeatherObservationsActivity>(intent)
        waitForUiWork()
        return scenario
    }

    private fun waitForUiWork() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
    }

    private fun observation(
        stationId: String,
        stationName: String,
        timestamp: Long,
        temperature: Float,
        distanceKm: Float,
        stationType: String = "UNKNOWN",
    ): ObservationEntity {
        return ObservationEntity(
            stationId = stationId,
            stationName = stationName,
            timestamp = timestamp,
            temperature = temperature,
            condition = "Clear",
            locationLat = lat,
            locationLon = lon,
            distanceKm = distanceKm,
            stationType = stationType,
            fetchedAt = timestamp,
            api = "NWS",
        )
    }

    private fun clearTestPrefs(name: String) {
        context.getSharedPreferences("${name}_test_default", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
