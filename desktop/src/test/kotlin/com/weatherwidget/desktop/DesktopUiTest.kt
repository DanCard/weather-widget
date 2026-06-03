package com.weatherwidget.desktop

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
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
                onUpdateLocation = {},
                onUpdateConfig = {},
                onOpenSettings = {}
            )
        }

        // Verify location and temperature are displayed
        composeTestRule.onNodeWithText("Mountain View").assertExists()
        composeTestRule.onNodeWithText("72.0°").assertExists()
        composeTestRule.onNodeWithText("Sunny").assertExists()
    }

    @Test
    fun testViewModeToggle() {
        var updatedConfig: DesktopConfig? = null
        composeTestRule.setContent {
            WidgetPopup(
                config = stubConfig,
                forecast = stubForecast,
                onUpdateLocation = {},
                onUpdateConfig = { updatedConfig = it },
                onOpenSettings = {}
            )
        }

        // Initially in DAILY mode (default)
        composeTestRule.onNodeWithText("Hourly").performClick()
        
        assert(updatedConfig?.viewMode == "HOURLY")
    }

    @Test
    fun testSettingsApiKeyEntry() {
        var savedConfig: DesktopConfig? = null
        composeTestRule.setContent {
            SettingsWindow(
                config = stubConfig,
                onClose = {},
                onSave = { savedConfig = it }
            )
        }

        // Enter an API key for Tomorrow.io
        composeTestRule.onNodeWithTag("api_key_TOMORROW_IO").performTextInput("test-key-123")
        composeTestRule.onNodeWithTag("save_settings").performClick()

        assert(savedConfig?.apiKeys?.get("TOMORROW_IO") == "test-key-123")
    }

    @Test
    fun testSettingsSourceToggle() {
        var savedConfig: DesktopConfig? = null
        val configWithMultipleSources = stubConfig.copy(visibleSources = listOf("NWS", "OPEN_METEO"))
        
        composeTestRule.setContent {
            SettingsWindow(
                config = configWithMultipleSources,
                onClose = {},
                onSave = { savedConfig = it }
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
    fun testFormatTrayTemperatureUsesOneDecimal() {
        assertEquals("72.6", formatTrayTemperature(72.6f))
        assertEquals("72.0", formatTrayTemperature(72f))
    }
}
