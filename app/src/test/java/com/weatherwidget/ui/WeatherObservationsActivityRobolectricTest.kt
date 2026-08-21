package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ObservationOrigin
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

        stateManager = WidgetStateManager(context)
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
    fun `blend, observations and fetch logs use separate tabs`() {
        launchActivity().onActivity { activity ->
            val blendTab = activity.findViewById<TextView>(R.id.blend_tab)
            val observationsTab = activity.findViewById<TextView>(R.id.observations_tab)
            val fetchLogsTab = activity.findViewById<TextView>(R.id.fetch_logs_tab)
            val blendContent = activity.findViewById<View>(R.id.blend_content)
            val observationsContent = activity.findViewById<View>(R.id.observations_content)
            val fetchLogsContent = activity.findViewById<View>(R.id.fetch_logs_content)

            fun assertOnly(selected: TextView, content: View) {
                listOf(blendTab, observationsTab, fetchLogsTab).forEach { tab ->
                    assertEquals("${tab.text} selected", tab === selected, tab.isSelected)
                }
                listOf(blendContent, observationsContent, fetchLogsContent).forEach { view ->
                    assertEquals(if (view === content) View.VISIBLE else View.GONE, view.visibility)
                }
            }

            // Observations tab is the default tab.
            assertOnly(observationsTab, observationsContent)

            fetchLogsTab.performClick()
            assertOnly(fetchLogsTab, fetchLogsContent)

            blendTab.performClick()
            assertOnly(blendTab, blendContent)

            observationsTab.performClick()
            assertOnly(observationsTab, observationsContent)
        }
    }

    @Test
    fun `selected fetch logs tab survives activity recreation`() {
        val scenario = launchActivity()
        scenario.onActivity { activity ->
            activity.findViewById<TextView>(R.id.fetch_logs_tab).performClick()
        }

        scenario.recreate()

        scenario.onActivity { activity ->
            assertFalse(activity.findViewById<TextView>(R.id.observations_tab).isSelected)
            assertTrue(activity.findViewById<TextView>(R.id.fetch_logs_tab).isSelected)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.observations_content).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.fetch_logs_content).visibility)
        }
    }

    @Test
    fun `last chosen tab is remembered across fresh activity launches`() {
        val scenario1 = launchActivity()
        scenario1.onActivity { activity ->
            assertTrue(activity.findViewById<TextView>(R.id.observations_tab).isSelected)
            activity.findViewById<TextView>(R.id.fetch_logs_tab).performClick()
            assertTrue(activity.findViewById<TextView>(R.id.fetch_logs_tab).isSelected)
        }
        scenario1.close()

        val scenario2 = launchActivity()
        scenario2.onActivity { activity ->
            assertTrue(activity.findViewById<TextView>(R.id.fetch_logs_tab).isSelected)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.fetch_logs_content).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.observations_content).visibility)

            activity.findViewById<TextView>(R.id.blend_tab).performClick()
            assertTrue(activity.findViewById<TextView>(R.id.blend_tab).isSelected)
        }
        scenario2.close()

        val scenario3 = launchActivity()
        scenario3.onActivity { activity ->
            assertTrue(activity.findViewById<TextView>(R.id.blend_tab).isSelected)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.blend_content).visibility)
        }
        scenario3.close()
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

    // Regression: the device had recently been in Austin, so a fresh (<24h) KATT observation stored
    // under the Austin location lingered in the table. The observations list is location-blind no
    // longer — it must scope to the current (Bay Area) widget location and drop the Austin row, which
    // otherwise showed up mid-list with a bogus "3.6 mi" (its distance was computed back in Austin).
    @Test
    fun `nws mode excludes observations fetched at a different location`() {
        runBlocking {
            database.observationDao().insertAll(
                listOf(
                    ObservationEntity(
                        stationId = "KATT",
                        stationName = "Austin City Austin Camp Mabry",
                        timestamp = now - 30_000L,
                        temperature = 91.0f,
                        condition = "Clear",
                        locationLat = 30.32079,
                        locationLon = -97.76048,
                        distanceKm = 5.8f,
                        stationType = "OFFICIAL",
                        fetchedAt = now - 30_000L,
                        api = "NWS",
                    ),
                ),
            )
        }

        val scenario = launchActivity()

        scenario.onActivity { activity ->
            val adapter = activity.findViewById<RecyclerView>(R.id.observations_list).adapter as WeatherObservationsActivity.ObservationAdapter
            val stationIds = adapter.items.map { it.stationId }

            assertFalse("Austin observation must not leak into the Bay Area list", stationIds.contains("KATT"))
            assertEquals(listOf("AW020", "KNUQ"), stationIds)
        }
    }

    // Regression (Samsung, 2026-07-15): the list showed 6 NWS stations when only MAX_RETRIES = 5 are
    // ever fetched. The device had been at 37.3414/-122.0422 until 16:05 the previous day; LSGC1 (Los
    // Gatos) was polled only from there and was never refreshed after the move. Unlike the Austin row
    // above, that site is just 0.075°/0.047° away — INSIDE the ±0.1° SQL box — so the DAO happily
    // returned it and it rendered as a 6th entry until it aged out of the 24h window. The box is a
    // coarse pre-filter; the list must additionally collapse to the current site.
    @Test
    fun `nws mode excludes a stale nearby site that the proximity box admits`() {
        runBlocking {
            database.observationDao().insertAll(
                listOf(
                    ObservationEntity(
                        stationId = "LSGC1",
                        stationName = "LOS GATOS",
                        timestamp = now - 30_000L,
                        temperature = 74.0f,
                        condition = "Clear",
                        // The previous site: inside the 0.1° box, outside the 0.002° same-site box.
                        locationLat = 37.3414,
                        locationLon = -122.0422,
                        distanceKm = 17.25f,
                        stationType = "PERSONAL",
                        fetchedAt = now - 30_000L,
                        api = "NWS",
                    ),
                ),
            )
        }

        val scenario = launchActivity()

        scenario.onActivity { activity ->
            val adapter = activity.findViewById<RecyclerView>(R.id.observations_list).adapter as WeatherObservationsActivity.ObservationAdapter
            val stationIds = adapter.items.map { it.stationId }

            assertFalse("A station from the previous site must not linger in the list", stationIds.contains("LSGC1"))
            assertEquals(listOf("AW020", "KNUQ"), stationIds)
        }
    }

    // KPAO 2026-07-13 regression: a Synoptic web-fallback reading of 50°F failed upstream QC
    // (spatial value check 105). The list must surface the failure instead of the bogus value:
    // badge reads "failed QC check" in the alert color and the temperature renders as "—".
    @Test
    fun `qc-failed observation renders failure badge and no temperature`() {
        runBlocking {
            database.observationDao().insertAll(
                listOf(
                    observation("KPAO", "Palo Alto Airport", now - 5_000L, 50.0f, 6.1f, stationType = "OFFICIAL")
                        .copy(isWebFallback = true, qcFailed = true),
                ),
            )
        }

        val scenario = launchActivity()

        scenario.onActivity { activity ->
            val recycler = activity.findViewById<RecyclerView>(R.id.observations_list)
            val adapter = recycler.adapter as WeatherObservationsActivity.ObservationAdapter

            fun bind(stationId: String): WeatherObservationsActivity.ObservationAdapter.ViewHolder {
                val holder = adapter.onCreateViewHolder(recycler, 0)
                adapter.onBindViewHolder(holder, adapter.items.indexOfFirst { it.stationId == stationId })
                return holder
            }

            val flagged = bind("KPAO")
            assertEquals("OFFICIAL (Failed QC check)", flagged.stationTypeBadge.text.toString())
            assertEquals(android.graphics.Color.parseColor("#FF3366"), flagged.stationTypeBadge.currentTextColor)
            assertEquals("—", flagged.temperature.text.toString())

            // A clean sibling keeps the normal rendering — the QC branch must not leak.
            val clean = bind("KNUQ")
            assertEquals("OFFICIAL (API)", clean.stationTypeBadge.text.toString())
            assertEquals("68.0°", clean.temperature.text.toString())
        }
    }

    // A station whose newest reading predates the blend's 3h decay window contributes nothing to the
    // displayed temperature. Badging it "API" implied it was still feeding the blend, so the row now
    // reads "Stale" in the alert color and blanks its value — the same treatment as a QC rejection.
    @Test
    fun `station past the blend decay window renders stale badge and no temperature`() {
        val staleMs = now - ObservationOrigin.BLEND_MAX_AGE_MS - 60_000L
        runBlocking {
            database.observationDao().insertAll(
                listOf(
                    observation("KPAO", "Palo Alto Airport", staleMs, 50.0f, 6.1f, stationType = "OFFICIAL"),
                ),
            )
        }

        val scenario = launchActivity()

        scenario.onActivity { activity ->
            val recycler = activity.findViewById<RecyclerView>(R.id.observations_list)
            val adapter = recycler.adapter as WeatherObservationsActivity.ObservationAdapter

            fun bind(stationId: String): WeatherObservationsActivity.ObservationAdapter.ViewHolder {
                val holder = adapter.onCreateViewHolder(recycler, 0)
                adapter.onBindViewHolder(holder, adapter.items.indexOfFirst { it.stationId == stationId })
                return holder
            }

            val stale = bind("KPAO")
            assertEquals("OFFICIAL (Stale)", stale.stationTypeBadge.text.toString())
            assertEquals(android.graphics.Color.parseColor("#FF3366"), stale.stationTypeBadge.currentTextColor)
            assertEquals("—", stale.temperature.text.toString())

            // A reporting sibling keeps the normal rendering — the staleness branch must not leak.
            val fresh = bind("KNUQ")
            assertEquals("OFFICIAL (API)", fresh.stationTypeBadge.text.toString())
            assertEquals("68.0°", fresh.temperature.text.toString())
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
    fun `clicking an official NWS station opens its time-series web page`() {
        val scenario = launchActivity()

        scenario.onActivity { activity ->
            val adapter = activity.findViewById<RecyclerView>(R.id.observations_list).adapter as WeatherObservationsActivity.ObservationAdapter
            adapter.onItemClick(adapter.items.first { it.stationId == "KNUQ" })
            shadowOf(Looper.getMainLooper()).idle()

            val started = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, started.action)
            assertEquals("https://www.weather.gov/wrh/timeseries?site=KNUQ", started.dataString)
        }
    }

    @Test
    fun `clicking a personal NWS station opens its time-series web page`() {
        val scenario = launchActivity()

        scenario.onActivity { activity ->
            val adapter = activity.findViewById<RecyclerView>(R.id.observations_list).adapter as WeatherObservationsActivity.ObservationAdapter
            adapter.onItemClick(adapter.items.first { it.stationId == "AW020" })
            shadowOf(Looper.getMainLooper()).idle()

            val started = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, started.action)
            assertEquals("https://www.weather.gov/wrh/timeseries?site=AW020", started.dataString)
        }
    }

    @Test
    fun `clicking a non-NWS station opens nothing`() {
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.WEATHER_API))
        stateManager.setCurrentDisplaySource(widgetId, WeatherSource.NWS)
        val scenario = launchActivity()

        scenario.onActivity { activity ->
            // Cycle NWS -> WeatherAPI so the active source has no per-station web page.
            activity.findViewById<TextView>(R.id.api_source_button).performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }

        scenario.onActivity { activity ->
            val adapter = activity.findViewById<RecyclerView>(R.id.observations_list).adapter as WeatherObservationsActivity.ObservationAdapter
            adapter.onItemClick(adapter.items.first { it.stationId == "WEATHER_API_MAIN" })
            shadowOf(Looper.getMainLooper()).idle()

            assertNull("Non-NWS rows have no link and must not start an activity", shadowOf(activity).nextStartedActivity)
        }
    }

    @Test
    fun `cycling to silurian hides cached model rows but keeps source error logs`() {
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
            assertTrue(stationIds.isEmpty())
            assertEquals("No recent observations found for Silurian.", subtitle)
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

    // Samsung regression: the widget launches this activity with FLAG_ACTIVITY_NEW_TASK. If it shared
    // MainActivity's (default) task affinity, One UI Home would foreground the MainActivity-rooted task
    // and finishing here would reveal the "Welcome to Weather Widget" screen. A distinct task affinity
    // keeps it in its own task so Close returns to the home screen instead.
    @Test
    fun `observations activity does not share a task with the welcome MainActivity`() {
        val pm = context.packageManager
        val obsInfo = pm.getActivityInfo(
            ComponentName(context, WeatherObservationsActivity::class.java), 0)
        val mainInfo = pm.getActivityInfo(
            ComponentName(context, MainActivity::class.java), 0)
        assertNotEquals(
            "Observations must live in its own task so closing it cannot reveal the Welcome screen",
            mainInfo.taskAffinity,
            obsInfo.taskAffinity,
        )
    }

    @Test
    fun `clicking Close finishes the activity without launching MainActivity`() {
        launchActivity().onActivity { activity ->
            activity.findViewById<Button>(R.id.close_button).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Close must finish the activity", activity.isFinishing)
            assertNull(
                "Close must not start another activity (the Welcome screen)",
                shadowOf(activity).nextStartedActivity,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Location scoping. Regression guards for the 2026-08-15 Samsung Fold incident: the screen showed
    // "No recent observations found for NWS" through eleven automatic reloads while five NWS stations
    // sat in the DB. See plans/260815-observations-empty-list-stale-location-scope-opus.md.
    // ---------------------------------------------------------------------------------------------

    /** A ~0.8 km GPS excursion from the seeded site: outside sameSite, well inside the query box. */
    private val excursionLat = lat - 0.006
    private val excursionLon = lon - 0.006

    @Test
    fun `an excursion fragment holding only synthetic rows does not empty the stations list`() {
        // The excursion site received only the `<SOURCE>_MAIN` backfill rows — no NWS station pull
        // ever ran there — so collapsing onto it purely because it is nearest leaves the NWS filter
        // with nothing to show.
        runBlocking {
            database.observationDao().insertAll(
                listOf(
                    observationAt("OPEN_METEO_MAIN", "OM: Current", now, 66.9f, excursionLat, excursionLon),
                    observationAt("SILURIAN_MAIN", "Silurian: Current", now, 67.7f, excursionLat, excursionLon),
                    observationAt("TOMORROW_IO_MAIN", "Tmrw: Current", now, 69.1f, excursionLat, excursionLon),
                ),
            )
        }
        moveWidgetTo(excursionLat, excursionLon)

        launchActivity().onActivity { activity ->
            assertEquals(
                "The real site's stations must win over a nearer fragment that has none",
                listOf("AW020", "KNUQ"),
                adapterOf(activity).items.map { it.stationId },
            )
            assertNotEquals(
                context.getString(R.string.obs_subtitle_none_found, WeatherSource.NWS.displayName),
                activity.findViewById<TextView>(R.id.subtitle).text.toString(),
            )
        }
    }

    @Test
    fun `the list re-scopes when the location changes while the activity is alive`() {
        // The activity used to resolve its location once in onCreate, so a device that moved mid-session
        // kept querying the coordinate it had left until the activity was recreated.
        val movedLat = lat + 0.083 // still inside the ±0.1° query box, a different site
        val movedLon = lon
        runBlocking {
            database.observationDao().insertAll(
                listOf(observationAt("KHWD", "Hayward Executive", now, 64.0f, movedLat, movedLon)),
            )
        }

        val scenario = launchActivity()
        scenario.onActivity { activity ->
            assertEquals(listOf("AW020", "KNUQ"), adapterOf(activity).items.map { it.stationId })
        }

        moveWidgetTo(movedLat, movedLon)

        scenario.onActivity { activity ->
            activity.loadObservations()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                "A reload after the device moved must query the new coordinate",
                listOf("KHWD"),
                adapterOf(activity).items.map { it.stationId },
            )
        }
    }

    @Test
    fun `an empty 24h window falls back to older rows and says how old they are`() {
        // Far enough that nothing already seeded is inside the query box: this site's only rows are
        // three days old, so the recent window is genuinely empty.
        val remoteLat = 38.0
        val remoteLon = -122.9
        val threeDaysAgo = now - 3 * 24 * 60 * 60 * 1000L
        runBlocking {
            database.observationDao().insertAll(
                listOf(observationAt("KAPC", "Napa County", threeDaysAgo, 58.0f, remoteLat, remoteLon)),
            )
        }
        moveWidgetTo(remoteLat, remoteLon)

        launchActivity().onActivity { activity ->
            assertEquals(
                "Older rows beat a blank list — reaching further back in the DB is free",
                listOf("KAPC"),
                adapterOf(activity).items.map { it.stationId },
            )
            assertEquals(
                context.getString(R.string.obs_subtitle_stale, WeatherSource.NWS.displayName, "3d"),
                activity.findViewById<TextView>(R.id.subtitle).text.toString(),
            )
        }
    }

    private fun adapterOf(activity: WeatherObservationsActivity) =
        activity.findViewById<RecyclerView>(R.id.observations_list).adapter
            as WeatherObservationsActivity.ObservationAdapter

    private fun moveWidgetTo(newLat: Double, newLon: Double) {
        SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME).edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", newLat.toFloat())
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", newLon.toFloat())
            .commit()
    }

    private fun observationAt(
        stationId: String,
        stationName: String,
        timestamp: Long,
        temperature: Float,
        locationLat: Double,
        locationLon: Double,
    ): ObservationEntity = observation(stationId, stationName, timestamp, temperature, 0f)
        .copy(locationLat = locationLat, locationLon = locationLon)

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
