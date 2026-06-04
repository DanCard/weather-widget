package com.weatherwidget.desktop

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DesktopUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val stubForecast = ForecastResult(
        currentTemp = 72f,
        currentCondition = "Sunny",
        daily = listOf(
            DailyForecast("2026-06-01", 75f, 55f, "Sunny", precipProbability = 0),
            DailyForecast("2026-06-02", 78f, 58f, "Cloudy", precipProbability = 10)
        ),
        hourly = listOf(
            HourlyForecast(System.currentTimeMillis(), 72f, "Sunny")
        )
    )

    private val stubConfig = DesktopConfig(
        lat = 37.4220,
        lon = -122.0841,
        label = "Mountain View",
        source = "Manual"
    )

    @Test
    fun testPopupRendersForecast() {
        composeTestRule.setContent {
            WidgetPopup(
                config = stubConfig,
                forecast = stubForecast,
                dataStatus = com.weatherwidget.data.model.DataStatus.Live(System.currentTimeMillis()),
                onUpdateLocation = {},
                onUpdateConfig = {},
                onOpenSettings = {},
                onOpenObservations = {},
            )
        }

        // Verify location chrome is displayed and daily forecast renders through the canvas surface.
        composeTestRule.onNodeWithText("Mountain View").assertExists()
        composeTestRule.onNodeWithTag("daily_forecast_surface").assertExists()
    }

    @Test
    fun testViewModeToggle() {
        var updatedConfig: DesktopConfig? = null
        composeTestRule.setContent {
            WidgetPopup(
                config = stubConfig,
                forecast = stubForecast,
                dataStatus = com.weatherwidget.data.model.DataStatus.Live(System.currentTimeMillis()),
                onUpdateLocation = {},
                onUpdateConfig = { updatedConfig = it },
                onOpenSettings = {},
                onOpenObservations = {},
            )
        }

        // Initially in DAILY mode (default)
        composeTestRule.onNodeWithText("H").performClick()

        assert(updatedConfig?.viewMode == "HOURLY")
    }

    @Test
    fun testCloudCoverToggle() {
        var updatedConfig: DesktopConfig? = null
        composeTestRule.setContent {
            WidgetPopup(
                config = stubConfig.copy(viewMode = "HOURLY"),
                forecast = stubForecast,
                dataStatus = com.weatherwidget.data.model.DataStatus.Live(System.currentTimeMillis()),
                onUpdateLocation = {},
                onUpdateConfig = { updatedConfig = it },
                onOpenSettings = {},
                onOpenObservations = {},
            )
        }

        // Tapping the cloud emoji switcher in temperature graph (HOURLY)
        composeTestRule.onNodeWithText("☁️").performClick()
        assertEquals("CLOUD_COVER", updatedConfig?.viewMode)
    }

    @Test
    fun testHourlyNavigationStepsBySixHours() {
        var updatedConfig: DesktopConfig? = null
        composeTestRule.setContent {
            WidgetPopup(
                config = stubConfig.copy(viewMode = "HOURLY"),
                forecast = stubForecast,
                dataStatus = com.weatherwidget.data.model.DataStatus.Live(System.currentTimeMillis()),
                onUpdateLocation = {},
                onUpdateConfig = { updatedConfig = it },
                onOpenSettings = {},
                onOpenObservations = {},
            )
        }

        composeTestRule.onNodeWithTag("hourly_nav_right").performClick()
        assertEquals(6, updatedConfig?.hourlyOffset)

        updatedConfig = null
        composeTestRule.onNodeWithTag("hourly_nav_left").performClick()
        assertEquals(-6, updatedConfig?.hourlyOffset)
    }

    @Test
    fun testHourlyNavigationDisablesAtBounds() {
        composeTestRule.setContent {
            WidgetPopup(
                config = stubConfig.copy(viewMode = "HOURLY", hourlyOffset = -720),
                forecast = stubForecast,
                dataStatus = com.weatherwidget.data.model.DataStatus.Live(System.currentTimeMillis()),
                onUpdateLocation = {},
                onUpdateConfig = {},
                onOpenSettings = {},
                onOpenObservations = {},
            )
        }

        composeTestRule.onNodeWithTag("hourly_nav_left").assertIsNotEnabled()
    }

    @Test
    fun testSettingsApiKeyEntry() {
        var savedConfig: DesktopConfig? = null
        composeTestRule.setContent {
            SettingsWindow(
                config = stubConfig,
                onClose = {},
                onSave = { savedConfig = it },
                onExit = {}
            )
        }

        // Enter an API key for Tomorrow.io
        composeTestRule.onNodeWithTag("api_key_TOMORROW_IO").performTextInput("test-key-123")
        composeTestRule.onNodeWithTag("save_settings").performClick()

        assert(savedConfig?.apiKeys?.get("TOMORROW_IO") == "test-key-123")
    }

    @Test
    fun testSettingsExitButtonInvokesOnExit() {
        var exited = false
        composeTestRule.setContent {
            SettingsWindow(
                config = stubConfig,
                onClose = {},
                onSave = {},
                onExit = { exited = true }
            )
        }

        composeTestRule.onNodeWithTag("exit_app").performClick()

        assert(exited)
    }

    @Test
    fun testSettingsSourceToggle() {
        var savedConfig: DesktopConfig? = null
        val configWithMultipleSources = stubConfig.copy(visibleSources = listOf("NWS", "OPEN_METEO"))
        
        composeTestRule.setContent {
            SettingsWindow(
                config = configWithMultipleSources,
                onClose = {},
                onSave = { savedConfig = it },
                onExit = {}
            )
        }

        // Uncheck NWS
        composeTestRule.onNodeWithTag("source_checkbox_NWS").performClick()
        composeTestRule.onNodeWithTag("save_settings").performClick()

        assert(savedConfig?.visibleSources?.contains("NWS") == false)
        assert(savedConfig?.visibleSources?.size == 1)
    }

    @Test
    fun testTemperatureTrayPainterRenders() {
        composeTestRule.setContent {
            val textMeasurer = rememberTextMeasurer()
            val painter = androidx.compose.runtime.remember {
                TemperatureTrayPainter(72.6f, textMeasurer)
            }
            androidx.compose.foundation.Canvas(modifier = Modifier.size(64.dp)) {
                with(painter) {
                    draw(size)
                }
            }
        }
        
        // This is a smoke test to ensure no crash during rendering.
        composeTestRule.onNodeWithTag("dummy").assertDoesNotExist()
    }

    @Test
    fun testTemperatureTrayPainterRendersPlaceholder() {
        composeTestRule.setContent {
            val textMeasurer = rememberTextMeasurer()
            val painter = androidx.compose.runtime.remember {
                TemperatureTrayPainter(null, textMeasurer)
            }
            androidx.compose.foundation.Canvas(modifier = Modifier.size(64.dp)) {
                with(painter) {
                    draw(size)
                }
            }
        }

        // This is a smoke test to ensure no crash during loading-state rendering.
        composeTestRule.onNodeWithTag("dummy").assertDoesNotExist()
    }

    @Test
    fun testTemperatureGraphRendersWithPreparedActuals() {
        val now = System.currentTimeMillis()
        val hour = 60 * 60 * 1000L
        composeTestRule.setContent {
            TemperatureGraph(
                hourly = listOf(
                    HourlyForecast(now - hour, 70f, "Clear", source = WeatherSource.NWS.id),
                    HourlyForecast(now, 72f, "Clear", source = WeatherSource.NWS.id),
                    HourlyForecast(now + hour, 74f, "Clear", source = WeatherSource.NWS.id),
                ),
                currentTemp = 71f,
                currentObservedAt = now - 30 * 60 * 1000L,
                observations = listOf(
                    ObservationReading(
                        stationId = "S1",
                        stationName = "Station 1",
                        timestamp = now - 45 * 60 * 1000L,
                        temperature = 70.5f,
                        condition = "observed",
                        locationLat = 37.4220,
                        locationLon = -122.0841,
                        distanceKm = 1f,
                        api = WeatherSource.NWS.id,
                    ),
                    ObservationReading(
                        stationId = "S2",
                        stationName = "Station 2",
                        timestamp = now - 30 * 60 * 1000L,
                        temperature = 71.5f,
                        condition = "observed",
                        locationLat = 37.4220,
                        locationLon = -122.0841,
                        distanceKm = 3f,
                        api = WeatherSource.NWS.id,
                    ),
                ),
                displaySourceId = WeatherSource.NWS.id,
                latitude = 37.4220,
                longitude = -122.0841,
                modifier = Modifier.size(420.dp, 220.dp),
            )
        }

        composeTestRule.onNodeWithTag("dummy").assertDoesNotExist()
    }

    @Test
    fun testFormatTrayTemperatureUsesOneDecimal() {
        assertEquals("72.6", formatTrayTemperature(72.6f))
        assertEquals("72.0", formatTrayTemperature(72f))
    }
}
