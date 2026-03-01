package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.R
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.AndroidTestDatabase
import com.weatherwidget.testutil.AndroidTestWidgetState
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for WeatherObservationsActivity.
 * Verifies that the activity correctly uses the weather source from the widget that launched it.
 */
@RunWith(AndroidJUnit4::class)
class WeatherObservationsSourceIntegrationTest : IsolatedIntegrationTest("weather_observations_source") {

    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 12345

    @Before
    override fun setup() {
        super.setup()
        stateManager = WidgetStateManager(context, db.appLogDao())
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))
        stateManager.clearWidgetState(testWidgetId)
    }

    @After
    override fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        super.cleanup()
    }

    @Test
    fun activityFiltersObservationsBySource() {
        runBlocking {
            // 1. Insert mixed observations
            val now = System.currentTimeMillis() - 3600000 // 1 hour ago
            val nwsObs = ObservationEntity(
                stationId = "KSJC",
                stationName = "San Jose Airport",
                timestamp = now,
                temperature = 70f,
                condition = "Clear",
                locationLat = 37.4,
                locationLon = -121.9,
                distanceKm = 5f,
                stationType = "OFFICIAL"
            )
            val meteoObs = ObservationEntity(
                stationId = "OPEN_METEO_MAIN",
                stationName = "Meteo: Current",
                timestamp = now,
                temperature = 72f,
                condition = "Partly Cloudy",
                locationLat = 37.4,
                locationLon = -121.9,
                distanceKm = 0f,
                stationType = "OFFICIAL"
            )
            db.observationDao().insertAll(listOf(nwsObs, meteoObs))
        }

        // 2. Launch activity with Open-Meteo selected
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.OPEN_METEO)
        
        val intent = Intent(context, WeatherObservationsActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, testWidgetId)
        }

        ActivityScenario.launch<WeatherObservationsActivity>(intent).use { scenario ->
            var items: List<ObservationEntity> = emptyList()
            // Poll for up to 2 seconds for background load to complete
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 2000) {
                scenario.onActivity { activity ->
                    val recyclerView = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.observations_list)
                    val adapter = recyclerView.adapter as WeatherObservationsActivity.ObservationAdapter
                    items = adapter.items
                }
                if (items.size == 1 && items.first().stationId == "OPEN_METEO_MAIN") break
                Thread.sleep(100)
            }
            
            assertTrue("Expected at least 1 Open-Meteo observation", items.isNotEmpty())
            assertTrue(
                "Expected Open-Meteo data in list: ${items.map { "${it.stationId}/${it.stationName}" }}",
                items.any { item ->
                    item.stationId == "OPEN_METEO_MAIN" ||
                        item.stationId == "Meteo" ||
                        item.stationName.contains("Meteo")
                },
            )
            assertTrue(
                "Open-Meteo view should exclude NWS rows: ${items.map { it.stationId }}",
                items.none { it.stationId == "KSJC" },
            )
        }
        
        // 3. Launch activity with NWS selected
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.NWS)
        ActivityScenario.launch<WeatherObservationsActivity>(intent).use { scenario ->
            var items: List<ObservationEntity> = emptyList()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 2000) {
                scenario.onActivity { activity ->
                    val recyclerView = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.observations_list)
                    val adapter = recyclerView.adapter as WeatherObservationsActivity.ObservationAdapter
                    items = adapter.items
                }
                if (items.any { it.stationId == "KSJC" }) break
                Thread.sleep(100)
            }
            
            assertTrue("Expected KSJC observation in NWS list: ${items.map { it.stationId }}", items.any { it.stationId == "KSJC" })
            assertTrue(
                "NWS view should exclude Open-Meteo rows: ${items.map { it.stationId }}",
                items.none { it.stationId.startsWith("OPEN_METEO_") },
            )
        }
    }

    @Test
    fun activityStarts_withCorrectSourceFromWidget() {
        // 1. Set widget source to OPEN_METEO in prefs
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.OPEN_METEO)

        // 2. Launch activity with the widget ID
        val intent = Intent(context, WeatherObservationsActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, testWidgetId)
        }

        ActivityScenario.launch<WeatherObservationsActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // 3. Verify the activity picked up the source from the widget
                val sourceButton = activity.findViewById<TextView>(R.id.api_source_button)
                assertEquals(
                    "Activity should start with source from widget",
                    WeatherSource.OPEN_METEO.shortDisplayName,
                    sourceButton.text.toString()
                )
            }
        }
    }

    @Test
    fun activityStarts_withDefaultSource_whenNoWidgetIdProvided() {
        // 1. Ensure a known visible order
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.WEATHER_API))
        
        // 2. Launch activity without widget ID
        val intent = Intent(context, WeatherObservationsActivity::class.java)

        ActivityScenario.launch<WeatherObservationsActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // 3. Verify it falls back to default (first visible)
                val sourceButton = activity.findViewById<TextView>(R.id.api_source_button)
                assertEquals(
                    "Activity should fallback to first visible source when no widget ID is provided",
                    WeatherSource.NWS.shortDisplayName,
                    sourceButton.text.toString()
                )
            }
        }
    }

    @Test
    fun activityChangesSource_updatesWidgetState() {
        // 1. Ensure visible sources order
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.NWS)

        // 2. Launch activity
        val intent = Intent(context, WeatherObservationsActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, testWidgetId)
        }

        ActivityScenario.launch<WeatherObservationsActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // 3. Simulate cycling the source
                activity.findViewById<TextView>(R.id.api_source_button).performClick()

                // 4. Verify activity internal state and button text
                val sourceButton = activity.findViewById<TextView>(R.id.api_source_button)
                assertEquals(
                    "Activity button should update after click",
                    WeatherSource.OPEN_METEO.shortDisplayName,
                    sourceButton.text.toString()
                )

                // 5. Verify widget state was updated in background
                assertEquals(
                    "Widget state should be updated to match activity selection",
                    WeatherSource.OPEN_METEO,
                    stateManager.getCurrentDisplaySource(testWidgetId)
                )
            }
        }
    }
}
