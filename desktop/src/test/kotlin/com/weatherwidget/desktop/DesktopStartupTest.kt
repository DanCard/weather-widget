package com.weatherwidget.desktop

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path

class DesktopStartupTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Verifies that the core desktop components can be initialized without crashing.
     * This acts as a "wiring" test to ensure Dependency Injection (manual) is correct.
     */
    @Test
    fun testCoreComponentInitialization() {
        val config = DesktopConfig(
            lat = 0.0,
            lon = 0.0,
            label = "Test",
            source = "Test"
        )
        
        // This should not throw any exceptions
        val service = DesktopWeatherService(config)
        service.close()
    }

    @Test
    fun testDesktopMainStartsWithoutCompositionLocalCrash() {
        val javaExecutable = Path(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            javaExecutable,
            "-Dweatherwidget.desktop.startupSmoke=true",
            "-cp",
            System.getProperty("java.class.path"),
            "com.weatherwidget.desktop.MainKt",
        )
            .redirectErrorStream(true)
            .start()

        val finished = process.waitFor(15, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        if (!finished) {
            process.destroyForcibly()
        }

        org.junit.Assert.assertTrue("Desktop main did not exit during startup smoke test. Output:\n$output", finished)
        org.junit.Assert.assertEquals("Desktop main failed during startup smoke test. Output:\n$output", 0, process.exitValue())
    }

    /**
     * Verifies that the configuration store can handle a missing config file
     * (the "first launch" scenario) without crashing.
     */
    @Test
    fun testFirstLaunchConfigHandling() {
        val tempDir = Files.createTempDirectory("weather-test")
        val configFile = tempDir.resolve("config.json")
        val store = DesktopConfigStore(configFile)
        
        val loaded = store.load()
        assert(loaded == null)
        
        val newConfig = DesktopConfig(37.0, -122.0, "CA", "Manual")
        store.save(newConfig)
        
        val reloaded = store.load()
        assert(reloaded?.lat == 37.0)
        
        // Cleanup
        Files.deleteIfExists(configFile)
        Files.deleteIfExists(tempDir)
    }

    /**
     * Verifies that the popup can handle a 'null' forecast state (the "loading" state)
     * which is the very first thing a user sees on startup.
     */
    @Test
    fun testPopupLoadingState() {
        val stubConfig = DesktopConfig(37.0, -122.0, "CA", "Manual")
        
        composeTestRule.setContent {
            WidgetPopup(
                config = stubConfig,
                forecast = null, // Simulated initial state
                onUpdateLocation = {},
                onUpdateConfig = {},
                onOpenSettings = {}
            )
        }

        // Verify it shows the loading message rather than crashing
        composeTestRule.onNodeWithText("Loading…").assertExists()
    }
}
