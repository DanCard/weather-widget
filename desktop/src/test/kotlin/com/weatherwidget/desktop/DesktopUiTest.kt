package com.weatherwidget.desktop

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyForecastSnapshot
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.ZoomStage
import com.weatherwidget.shared.util.DayClickResolver
import com.weatherwidget.shared.util.WeatherConditionResolver
import com.weatherwidget.shared.util.WeatherTimeUtils
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
        label = "Mountain View"
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

        // Verify daily forecast renders through the canvas surface.
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

        // Initially in DAILY mode (default). Tap the current temp to switch to HOURLY.
        composeTestRule.onNodeWithTag("current_temp_toggle").performClick()

        assert(updatedConfig?.viewMode == ViewMode.HOURLY)
    }

    @Test
    fun testCurrentTempAppearsOnceInSemantics() {
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
        composeTestRule.onNodeWithText("72.0°").assertExists()
    }

    @Test
    fun testSettingsExposesLocationAndObservations() {
        var locationClicked = false
        var observationsClicked = false
        composeTestRule.setContent {
            SettingsWindow(
                config = stubConfig,
                onClose = {},
                onSave = {},
                onExit = {},
                onUpdateLocation = { locationClicked = true },
                onOpenObservations = { observationsClicked = true }
            )
        }

        composeTestRule.onNodeWithText("Mountain View").assertExists()
        composeTestRule.onNodeWithTag("change_location_btn").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assert(locationClicked)

        composeTestRule.onNodeWithTag("open_observations_btn").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assert(observationsClicked)
    }

    @Test
    fun testCloudCoverToggle() {
        var updatedConfig: DesktopConfig? = null
        composeTestRule.setContent {
            WidgetPopup(
                config = stubConfig.copy(viewMode = ViewMode.HOURLY),
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
        assertEquals(ViewMode.CLOUD_COVER, updatedConfig?.viewMode)
    }

    @Test
    fun testPrecipitationHeaderClickTogglesPrecipitationView() {
        var updatedConfig: DesktopConfig? = null
        composeTestRule.setContent {
            WidgetPopup(
                config = stubConfig.copy(viewMode = ViewMode.DAILY),
                forecast = stubForecast.copy(
                    hourly = listOf(
                        com.weatherwidget.data.model.HourlyForecast(
                            dateTime = System.currentTimeMillis(),
                            temperature = 72f,
                            condition = "Rainy",
                            precipProbability = 80,
                            precipAmountMm = 2f
                        )
                    )
                ),
                dataStatus = com.weatherwidget.data.model.DataStatus.Live(System.currentTimeMillis()),
                onUpdateLocation = {},
                onUpdateConfig = { updatedConfig = it },
                onOpenSettings = {},
                onOpenObservations = {},
            )
        }

        // Click on "80%" in header to switch to PRECIPITATION graph
        composeTestRule.onNodeWithText("80%").performClick()
        assertEquals(ViewMode.PRECIPITATION, updatedConfig?.viewMode)
    }

    @Test
    fun testHourlyNavigationStepsByHalfTheVisibleSpan() {
        var updatedConfig: DesktopConfig? = null
        composeTestRule.setContent {
            WidgetPopup(
                config = stubConfig.copy(viewMode = ViewMode.HOURLY),
                forecast = stubForecast,
                dataStatus = com.weatherwidget.data.model.DataStatus.Live(System.currentTimeMillis()),
                onUpdateLocation = {},
                onUpdateConfig = { updatedConfig = it },
                onOpenSettings = {},
                onOpenObservations = {},
            )
        }

        // The arrow shifts by half the visible span (zoom-proportional), not a fixed step.
        val expectedJump = DesktopGraphUtils.navJumpHours(stubConfig.zoomFactor)

        composeTestRule.onNodeWithTag("hourly_nav_right").performClick()
        assertEquals(expectedJump, updatedConfig?.hourlyOffset)

        updatedConfig = null
        composeTestRule.onNodeWithTag("hourly_nav_left").performClick()
        assertEquals(-expectedJump, updatedConfig?.hourlyOffset)
    }

    @Test
    fun testHourlyNavigationDisablesAtBounds() {
        composeTestRule.setContent {
            WidgetPopup(
                config = stubConfig.copy(viewMode = ViewMode.HOURLY, hourlyOffset = -720),
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

    @Test
    fun headerObservationsButtonOpensObservations() {
        var opened = false
        composeTestRule.setContent {
            WidgetPopup(
                config = stubConfig.copy(viewMode = ViewMode.HOURLY),
                forecast = stubForecast,
                dataStatus = com.weatherwidget.data.model.DataStatus.Live(System.currentTimeMillis()),
                onUpdateLocation = {},
                onUpdateConfig = {},
                onOpenSettings = {},
                onOpenObservations = { opened = true },
            )
        }

        composeTestRule.onNodeWithTag("open_observations_header").performClick()
        assert(opened)
    }

    @Test
    fun headerGraphSelectorCyclesViews() {
        var viewMode: ViewMode? = null
        composeTestRule.setContent {
            WidgetPopup(
                config = stubConfig.copy(viewMode = ViewMode.HOURLY),
                forecast = stubForecast,
                dataStatus = com.weatherwidget.data.model.DataStatus.Live(System.currentTimeMillis()),
                onUpdateLocation = {},
                onUpdateConfig = { viewMode = it.viewMode },
                onOpenSettings = {},
                onOpenObservations = {},
            )
        }

        // HOURLY -> click selector -> CLOUD_COVER
        composeTestRule.onNodeWithTag("graph_selector").performClick()
        assertEquals(ViewMode.CLOUD_COVER, viewMode)

        // Reset and check CLOUD_COVER -> PRECIPITATION
        viewMode = null
        composeTestRule.setContent {
            WidgetPopup(
                config = stubConfig.copy(viewMode = ViewMode.CLOUD_COVER),
                forecast = stubForecast,
                dataStatus = com.weatherwidget.data.model.DataStatus.Live(System.currentTimeMillis()),
                onUpdateLocation = {},
                onUpdateConfig = { viewMode = it.viewMode },
                onOpenSettings = {},
                onOpenObservations = {},
            )
        }
        composeTestRule.onNodeWithTag("graph_selector").performClick()
        assertEquals(ViewMode.PRECIPITATION, viewMode)

        // Reset and check PRECIPITATION -> HOURLY
        viewMode = null
        composeTestRule.setContent {
            WidgetPopup(
                config = stubConfig.copy(viewMode = ViewMode.PRECIPITATION),
                forecast = stubForecast,
                dataStatus = com.weatherwidget.data.model.DataStatus.Live(System.currentTimeMillis()),
                onUpdateLocation = {},
                onUpdateConfig = { viewMode = it.viewMode },
                onOpenSettings = {},
                onOpenObservations = {},
            )
        }
        composeTestRule.onNodeWithTag("graph_selector").performClick()
        assertEquals(ViewMode.HOURLY, viewMode)
    }

    @Test
    fun testSettingsWindowRefreshAndLogsButtons() {
        var refreshClicked = false
        var logsClicked = false
        composeTestRule.setContent {
            SettingsWindow(
                config = stubConfig,
                onClose = {},
                onSave = {},
                onExit = {},
                onRefreshData = { refreshClicked = true },
                onViewAppLogs = { logsClicked = true }
            )
        }

        composeTestRule.onNodeWithTag("refresh_data_btn").performClick()
        composeTestRule.waitForIdle()
        assert(refreshClicked)

        composeTestRule.onNodeWithTag("view_app_logs_btn").performClick()
        composeTestRule.waitForIdle()
        assert(logsClicked)
    }

    @Test
    fun testAppLogsWindowShowsLogsAndFilters() {
        val tempDbPath = java.nio.file.Files.createTempFile("weather-ui-test", ".db")
        val database = com.weatherwidget.data.local.desktop.DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        val dao = com.weatherwidget.data.local.desktop.DesktopWeatherDao(database)
        try {
            dao.log("REFRESH", "Fetch success", "INFO")
            dao.log("REFRESH_FAIL", "Network error", "WARN")

            composeTestRule.setContent {
                AppLogsWindow(
                    weatherDao = dao,
                    onClose = {}
                )
            }

            composeTestRule.onNodeWithTag("app_logs_window").assertExists()
            composeTestRule.onNodeWithText("Fetch success").assertExists()
            composeTestRule.onNodeWithText("Network error").assertExists()

            // Filter out "error"
            composeTestRule.onNodeWithTag("app_log_filter_input").performTextInput("error")
            
            composeTestRule.waitUntil(timeoutMillis = 5000L) {
                try {
                    composeTestRule.onNodeWithText("Fetch success").assertDoesNotExist()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }

            composeTestRule.onNodeWithText("Network error").assertExists()
        } finally {
            java.nio.file.Files.deleteIfExists(tempDbPath)
        }
    }

    @Test
    fun testDayClickOpensFullDayWindow() {
        val now = java.time.LocalDateTime.of(2024, 6, 15, 10, 45)
        val clickedDate = java.time.LocalDate.of(2024, 6, 16)
        val wideZoom = DesktopGraphUtils.zoomFactorForStage(ZoomStage.WIDE)
        val fromTightZoom = dayClickConfig(
            stubConfig.copy(zoomFactor = 0f),
            clickedDate,
            emptyList(),
            DayClickResolver.DayTapZone.MAIN_COLUMN,
            now,
        )
        val fromWideZoom = dayClickConfig(
            stubConfig.copy(zoomFactor = 1f),
            clickedDate,
            emptyList(),
            DayClickResolver.DayTapZone.MAIN_COLUMN,
            now,
        )

        assertEquals(wideZoom, fromTightZoom.zoomFactor)
        assertEquals(wideZoom, fromWideZoom.zoomFactor)
        assertEquals(ViewMode.HOURLY, fromTightZoom.viewMode)

        val alignedCenter = WeatherTimeUtils.alignToNearestHourHalfUp(
            now.plusHours(fromTightZoom.hourlyOffset.toLong()),
        )
        assertEquals(clickedDate.atStartOfDay(), alignedCenter.minusHours(ZoomStage.WIDE.backHours))
        assertEquals(clickedDate.plusDays(1).atStartOfDay(), alignedCenter.plusHours(ZoomStage.WIDE.forwardHours))
    }

    /** Minimal [DesktopDailyDay] for routing tests; only icon + precip carry the decision. */
    private fun routingDay(
        date: java.time.LocalDate,
        iconName: String,
        forecastPrecip: Int?,
        snapshotPrecip: Int? = null,
    ) = DesktopDailyDay(
        date = date,
        label = "Today",
        forecast = DailyForecast(
            date = date.toString(),
            highTemp = 70f,
            lowTemp = 50f,
            condition = "n/a",
            precipProbability = forecastPrecip,
        ),
        actual = null,
        snapshot = snapshotPrecip?.let {
            DailyForecastSnapshot(
                date = date.toString(),
                highTemp = 70f,
                lowTemp = 50f,
                condition = "n/a",
                precipProbability = it,
                fetchedAt = 0L,
            )
        },
        solidHigh = null, solidLow = null, forecastHigh = null, forecastLow = null,
        ghostHigh = null, snapshotHigh = null, snapshotLow = null,
        iconCondition = null, iconName = iconName,
        isToday = true, isPast = false, cloudCoverRatio = null,
        dailyRainLabelText = null, nightRainLabelText = null, isClimateNormal = false,
    )

    @Test
    fun testDayClickRoutesLikeAndroid() {
        val date = java.time.LocalDate.now()
        val rain = WeatherConditionResolver.IC_RAIN
        val cloudy = WeatherConditionResolver.IC_CLOUDY
        val main = DayClickResolver.DayTapZone.MAIN_COLUMN
        val bottom = DayClickResolver.DayTapZone.BOTTOM_ICON

        assertEquals(
            ViewMode.PRECIPITATION,
            dayClickConfig(stubConfig, date, listOf(routingDay(date, rain, forecastPrecip = 16)), main).viewMode,
        )
        assertEquals(
            ViewMode.HOURLY,
            dayClickConfig(stubConfig, date, listOf(routingDay(date, rain, forecastPrecip = 15)), main).viewMode,
        )
        assertEquals(
            ViewMode.HOURLY,
            dayClickConfig(stubConfig, date, listOf(routingDay(date, cloudy, forecastPrecip = 90)), main).viewMode,
        )
        assertEquals(
            ViewMode.PRECIPITATION,
            dayClickConfig(
                stubConfig,
                date,
                listOf(routingDay(date, rain, forecastPrecip = null, snapshotPrecip = 40)),
                main,
            ).viewMode,
        )
        assertEquals(
            ViewMode.CLOUD_COVER,
            dayClickConfig(stubConfig, date, listOf(routingDay(date, cloudy, forecastPrecip = 0)), bottom).viewMode,
        )
    }

    @Test
    fun testDayClickPreservesZoomWhenNotEnteringFromDaily() {
        val date = java.time.LocalDate.now()
        val hourlyConfig = stubConfig.copy(viewMode = ViewMode.HOURLY, zoomFactor = 0.55f)
        val result = dayClickConfig(
            hourlyConfig,
            date,
            listOf(routingDay(date, WeatherConditionResolver.IC_CLEAR, forecastPrecip = 0)),
            DayClickResolver.DayTapZone.MAIN_COLUMN,
        )
        assertEquals(0.55f, result.zoomFactor)
    }
}
