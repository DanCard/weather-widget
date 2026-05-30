package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WeatherObservationsActivityRobolectricTest {
    private lateinit var context: Context
    private lateinit var database: WeatherDatabase
    private lateinit var stateManager: WidgetStateManager

    private val widgetId = 4201
    private val lat = 37.416885
    private val lon = -122.088776
    private var now = 0L
    
    // Test dispatcher for synchronous execution
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WeatherDatabase.setIsTesting(true)
        // Use synchronous in-memory database with direct executors to avoid background racing
        database = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(database)
        
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
        clearTestPrefs("weather_widget_prefs")
        clearTestPrefs("widget_state_prefs")
        clearTestPrefs("weather_prefs")

        now = System.currentTimeMillis()

        stateManager = WidgetStateManager(context, database.appLogDao())
        WeatherObservationsActivity.autoRefreshDebounceMs = 0L

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
                    observation("TOMORROW_IO_MAIN", "Tmrw: Current", now - 40_000L, 69.1f, 0f),
                ),
            )
            database.appLogDao().insert(
                AppLogEntity(
                    timestamp = now,
                    tag = "CURR_FETCH_START",
                    message = "reason=opportunistic_job targets=NWS,SILURIAN,WEATHER_API",
                ),
            )
            database.appLogDao().insert(
                AppLogEntity(
                    timestamp = now + 1_000L,
                    tag = "CURR_FETCH_DONE",
                    message = "reason=opportunistic_job targets=NWS,SILURIAN,WEATHER_API updated=3",
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
            repeat(1_100) { index ->
                database.appLogDao().insert(
                    AppLogEntity(
                        timestamp = now + 3_000L + index,
                        tag = "CURRENT_TEMP_DISPLAY",
                        message = "widget=4201 source=NWS displayTemp=68 index=$index",
                    ),
                )
            }
            database.appLogDao().insert(
                AppLogEntity(
                    timestamp = now + 5_000L,
                    tag = "CURR_FETCH_WORK_ENQUEUED",
                    message = "type=charging_loop reason=charging_loop opportunistic=false force=false policyDelayMinutes=10",
                ),
            )
            database.appLogDao().insert(
                AppLogEntity(
                    timestamp = now + 6_000L,
                    tag = "CURR_FETCH_SOURCE_RESULT",
                    message = "reason=charging_loop source=NWS success=true temp=70.0",
                ),
            )
        }
    }

    @After
    fun tearDown() {
        WeatherDatabase.resetInstanceForTesting()
        WeatherDatabase.setIsTesting(false)
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
        WeatherObservationsActivity.autoRefreshDebounceMs = 500L
        clearTestPrefs("weather_widget_prefs")
        clearTestPrefs("widget_state_prefs")
        clearTestPrefs("weather_prefs")
    }

    @Test
    fun `nws mode excludes silurian rows and shows current fetch logs`() {
        val scenario = launchActivity()

        scenario.onActivity { activity ->
            val adapter = activity.findViewById<RecyclerView>(R.id.observations_list).adapter as WeatherObservationsActivity.ObservationAdapter
            val stationIds = adapter.items.map { it.stationId }
            val subtitle = activity.findViewById<TextView>(R.id.subtitle).text.toString()
            val logs = activity.findViewById<TextView>(R.id.fetch_logs).text.toString()

            assertEquals(listOf("AW020", "KNUQ"), stationIds)
            assertEquals("Real-time data from nearby stations", subtitle)
            assertEquals("PERSONAL", adapter.items[0].stationType)
            assertFalse(logs.contains("No recent fetch logs for NWS"))
            assertFalse(logs.contains("No current observation fetch logs found for NWS"))
            assertTrue(logs.contains("enqueued type=charging_loop reason=charging_loop"))
            assertTrue(logs.contains("source reason=charging_loop source=NWS success=true temp=70.0"))
            assertTrue(logs.contains("start reason=opportunistic_job targets=NWS,SILURIAN,WEATHER_API"))
            assertTrue(logs.contains("done reason=opportunistic_job targets=NWS,SILURIAN,WEATHER_API updated=3"))
        }
    }

    @Test
    fun `nws mode excludes tomorrow io rows`() {
        val scenario = launchActivity()

        scenario.onActivity { activity ->
            val adapter = activity.findViewById<RecyclerView>(R.id.observations_list).adapter as WeatherObservationsActivity.ObservationAdapter
            val stationIds = adapter.items.map { it.stationId }

            assertFalse("Tomorrow.io observations should be excluded from NWS view", stationIds.contains("TOMORROW_IO_MAIN"))
            assertEquals(listOf("AW020", "KNUQ"), stationIds)
        }
    }

    @Test
    fun `visible activity reloads observations when new current observations are inserted`() {
        val scenario = launchActivity()
        val newerFetchAt = now + 3_600_000L

        runBlocking {
            database.observationDao().insertAll(
                listOf(
                    observation(
                        stationId = "AW020",
                        stationName = "AE6EO MOUNTAIN VIEW",
                        timestamp = newerFetchAt,
                        temperature = 74.5f,
                        distanceKm = 2.9f,
                        stationType = "PERSONAL",
                    ),
                ),
            )
        }
        shadowOf(Looper.getMainLooper()).idle()

        scenario.onActivity { activity ->
            val adapter = activity.findViewById<RecyclerView>(R.id.observations_list).adapter as WeatherObservationsActivity.ObservationAdapter
            val updated = adapter.items.first { it.stationId == "AW020" }

            assertEquals(newerFetchAt, updated.fetchedAt)
            assertEquals(74.5f, updated.temperature, 0.01f)
        }
    }

    @Test
    fun `visible activity reloads fetch logs when current observation diagnostics are inserted`() {
        val scenario = launchActivity()

        runBlocking {
            database.appLogDao().insert(
                AppLogEntity(
                    timestamp = now + 7_000L,
                    tag = "CURR_FETCH_WORK_REQUESTED",
                    message = "type=charging_loop reason=charging_loop decision=enqueue_delayed",
                ),
            )
        }
        shadowOf(Looper.getMainLooper()).idle()

        scenario.onActivity { activity ->
            val logs = activity.findViewById<TextView>(R.id.fetch_logs).text.toString()

            assertTrue(logs.contains("requested type=charging_loop reason=charging_loop decision=enqueue_delayed"))
        }
    }

    @Test
    fun `cycling to silurian shows only silurian observations and source error logs`() {
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.SILURIAN, WeatherSource.WEATHER_API))
        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.NWS)
        val scenario = launchActivity()

        scenario.onActivity { activity ->
            activity.findViewById<TextView>(R.id.api_source_button).performClick()
            // After click, the activity triggers loadObservations() and loadFetchLogs() on ioDispatcher
            // Ensure all pending work on main looper (like coroutine resumptions) finishes
            shadowOf(Looper.getMainLooper()).idle()
        }

        scenario.onActivity { activity ->
            val adapter = activity.findViewById<RecyclerView>(R.id.observations_list).adapter as WeatherObservationsActivity.ObservationAdapter
            val stationIds = adapter.items.map { it.stationId }
            val subtitle = activity.findViewById<TextView>(R.id.subtitle).text.toString()
            val logs = activity.findViewById<TextView>(R.id.fetch_logs).text.toString()
            val sourceButton = activity.findViewById<TextView>(R.id.api_source_button).text.toString()

            assertEquals("Silur", sourceButton)
            assertEquals(listOf("SILURIAN_MAIN", "SILURIAN_1"), stationIds)
            assertEquals("Latest reading from Silurian", subtitle)
            assertEquals("UNKNOWN", adapter.items[0].stationType)
            assertTrue(logs.contains("error error=timeout"))
        }
    }

    @Test
    fun `activityStarts_withCorrectSourceFromWidget`() {
        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.OPEN_METEO)
        val scenario = launchActivity()

        scenario.onActivity { activity ->
            val sourceButton = activity.findViewById<TextView>(R.id.api_source_button)
            assertEquals(
                "Activity should start with source from widget",
                WeatherSource.OPEN_METEO.shortDisplayName,
                sourceButton.text.toString()
            )
        }
    }

    @Test
    fun `activityStarts_withDefaultSource_whenNoWidgetIdProvided`() {
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.WEATHER_API))

        val intent = Intent(context, WeatherObservationsActivity::class.java)
        val scenario = ActivityScenario.launch<WeatherObservationsActivity>(intent)
        scenario.onActivity { it.ioDispatcher = testDispatcher }
        // Force a re-load now that we have the synchronous dispatcher
        scenario.onActivity { 
            it.findViewById<TextView>(R.id.api_source_button).performClick()
            it.findViewById<TextView>(R.id.api_source_button).performClick() 
            shadowOf(Looper.getMainLooper()).idle()
        } 
        // Actually, onCreate already ran. Let's try to inject dispatcher via scenario.onActivity before it runs? 
        // ActivityScenario.launch runs onCreate immediately.
        // Let's use ActivityScenario.onActivity to re-trigger the loads.
        scenario.onActivity {
            it.ioDispatcher = testDispatcher
            it.loadObservations()
            it.loadFetchLogs()
        }

        scenario.onActivity { activity ->
            val sourceButton = activity.findViewById<TextView>(R.id.api_source_button)
            assertEquals(
                "Activity should fallback to first visible source when no widget ID is provided",
                WeatherSource.NWS.shortDisplayName,
                sourceButton.text.toString()
            )
        }
    }

    private fun launchActivity(): ActivityScenario<WeatherObservationsActivity> {
        val intent =
            Intent(context, WeatherObservationsActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
        val scenario = ActivityScenario.launch<WeatherObservationsActivity>(intent)
        scenario.onActivity {
            it.ioDispatcher = testDispatcher
            it.loadObservations()
            it.loadFetchLogs()
        }
        return scenario
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
