package com.weatherwidget.desktop

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyForecastSnapshot
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.test.category.LongDuration
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
class DesktopWindowBringToFrontIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sourceRoot by lazy(::findDesktopSourceRoot)

    @Test
    fun `all desktop window composables wire showRequestId and use BringToFrontOnShow`() {
        val windowHosts = sourceRoot.resolve("DesktopWindowHosts.kt").readText()
        val observations = sourceRoot.resolve("ObservationsWindow.kt").readText()
        val history = sourceRoot.resolve("ForecastHistoryWindow.kt").readText()
        val logs = sourceRoot.resolve("AppLogsWindow.kt").readText()
        val stats = sourceRoot.resolve("StatisticsWindow.kt").readText()

        val checks = listOf(
            "PopupWindowHost in DesktopWindowHosts" to (windowHosts.contains("BringToFrontOnShow") && windowHosts.contains("showRequestId")),
            "SettingsWindowHost in DesktopWindowHosts" to (windowHosts.contains("fun SettingsWindowHost") && windowHosts.contains("showRequestId: Int")),
            "LocationPickerWindowHost in DesktopWindowHosts" to (windowHosts.contains("fun LocationPickerWindowHost") && windowHosts.contains("showRequestId: Int")),
            "IconGalleryWindowHost in DesktopWindowHosts" to (windowHosts.contains("fun IconGalleryWindowHost") && windowHosts.contains("showRequestId: Int")),
            "ObservationsWindow" to (observations.contains("BringToFrontOnShow") && observations.contains("showRequestId")),
            "ForecastHistoryWindow" to (history.contains("BringToFrontOnShow") && history.contains("showRequestId")),
            "AppLogsWindow" to (logs.contains("BringToFrontOnShow") && logs.contains("showRequestId")),
            "StatisticsWindow" to (stats.contains("BringToFrontOnShow") && stats.contains("showRequestId")),
        )

        for ((name, condition) in checks) {
            assertTrue("$name must wire showRequestId and BringToFrontOnShow", condition)
        }
    }

    @Test
    fun `DesktopUiApplication increments showRequestId counters for all window open triggers`() {
        val uiApp = sourceRoot.resolve("DesktopUiApplication.kt").readText()

        assertTrue("Must manage settingsShowRequestId", "var settingsShowRequestId" in uiApp)
        assertTrue("Must increment settingsShowRequestId on open settings", "settingsShowRequestId++" in uiApp)

        assertTrue("Must manage pickerShowRequestId", "var pickerShowRequestId" in uiApp)
        assertTrue("Must increment pickerShowRequestId on update location", "pickerShowRequestId++" in uiApp)

        assertTrue("Must manage obsShowRequestId", "var obsShowRequestId" in uiApp)
        assertTrue("Must increment obsShowRequestId on open observations", "obsShowRequestId++" in uiApp)

        assertTrue("Must manage historyShowRequestId", "var historyShowRequestId" in uiApp)
        assertTrue("Must increment historyShowRequestId on open history", "historyShowRequestId++" in uiApp)

        assertTrue("Must manage appLogsShowRequestId", "var appLogsShowRequestId" in uiApp)
        assertTrue("Must increment appLogsShowRequestId on view app logs", "appLogsShowRequestId++" in uiApp)

        assertTrue("Must manage iconGalleryShowRequestId", "var iconGalleryShowRequestId" in uiApp)
        assertTrue("Must increment iconGalleryShowRequestId on open icon gallery", "iconGalleryShowRequestId++" in uiApp)
    }

    @Test
    fun `clicking settings gear in popup repeatedly increments showRequestId`() {
        var settingsShowRequestId = 0
        var settingsVisible = false

        val sampleConfig = DesktopConfig(
            lat = 37.42,
            lon = -122.08,
            label = "Test Location",
            settings = DesktopSettings(visibleSources = listOf("NWS", "OPEN_METEO")),
        )
        val sampleForecast = ForecastSnapshot(
            raw = RawFetch(
                daily = listOf(DailyForecast(date = "2026-06-01", highTemp = 75f, lowTemp = 55f, condition = "Sunny", precipProbability = 0)),
                hourly = listOf(HourlyForecast(System.currentTimeMillis(), 72f, "Sunny")),
            ),
            resolved = ResolvedView(currentTemp = 72f, currentCondition = "Sunny"),
        )

        composeTestRule.setContent {
            WidgetPopup(
                config = sampleConfig,
                forecast = sampleForecast,
                dataStatus = DataStatus.Live(System.currentTimeMillis()),
                onUpdateLocation = {},
                onUpdateConfig = {},
                onOpenSettings = {
                    settingsVisible = true
                    settingsShowRequestId++
                },
                onOpenObservations = {},
                onOpenHistory = {},
            )
        }

        // First click
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        assertEquals("First click should show settings and increment request ID", 1, settingsShowRequestId)
        assertTrue("settingsVisible should be true", settingsVisible)

        // Second click (when settings screen is already open/rendered behind other windows)
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        assertEquals("Subsequent click must still increment request ID to surface window", 2, settingsShowRequestId)
    }

    @Test
    fun `clicking set location in settings repeatedly increments showRequestId`() {
        var pickerShowRequestId = 0
        var pickerVisible = false

        val sampleConfig = DesktopConfig(
            lat = 37.42,
            lon = -122.08,
            label = "Test Location",
            settings = DesktopSettings(visibleSources = listOf("NWS", "OPEN_METEO")),
        )

        composeTestRule.setContent {
            SettingsWindow(
                config = sampleConfig,
                onClose = {},
                onSave = {},
                onExit = {},
                onUpdateLocation = {
                    pickerVisible = true
                    pickerShowRequestId++
                },
            )
        }

        // First click
        composeTestRule.onNodeWithTag("set_location_btn").performClick()
        assertEquals("First click should show picker and increment request ID", 1, pickerShowRequestId)
        assertTrue("pickerVisible should be true", pickerVisible)

        // Second click (when picker is already open/rendered behind other windows)
        composeTestRule.onNodeWithTag("set_location_btn").performClick()
        assertEquals("Subsequent click must still increment request ID to surface window", 2, pickerShowRequestId)
    }

    private fun findDesktopSourceRoot(): File {
        val candidates = listOf(
            File("src/main/kotlin/com/weatherwidget/desktop"),
            File("desktop/src/main/kotlin/com/weatherwidget/desktop"),
        )
        return requireNotNull(candidates.firstOrNull { it.isDirectory }) {
            "Could not locate desktop main source root from ${File(".").absolutePath}"
        }
    }
}
