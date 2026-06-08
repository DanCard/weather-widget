package com.weatherwidget.desktop

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import org.junit.Assert.assertTrue

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

    @Test
    fun `test app launch and show`() {
        val tempDir = Files.createTempDirectory("weather-test-show")
        val appDir = tempDir.resolve("weather-widget")
        Files.createDirectories(appDir)

        val javaExecutable = Path(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            javaExecutable,
            "-cp",
            System.getProperty("java.class.path"),
            "com.weatherwidget.desktop.MainKt"
        )
            .redirectErrorStream(true)
        
        // Isolate the process config/data directory
        process.environment()["XDG_DATA_HOME"] = tempDir.toAbsolutePath().toString()

        val runningProcess = process.start()
        val reader = BufferedReader(InputStreamReader(runningProcess.inputStream))
        val outputLines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val readThread = kotlin.concurrent.thread(start = true, isDaemon = true, name = "process-reader") {
            runCatching {
                reader.forEachLine { line ->
                    outputLines.add(line)
                }
            }
        }

        try {
            // 1. Wait for the daemon to start
            var daemonStarted = false
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 10000L) {
                if (outputLines.any { it.contains("Starting headless WeatherDaemon") }) {
                    daemonStarted = true
                    break
                }
                Thread.sleep(100)
            }

            assertTrue("Daemon failed to start. Output:\n${outputLines.joinToString("\n")}", daemonStarted)

            // 2. Give the WatchService a brief moment to register
            Thread.sleep(1500)

            // 3. Touch the .show file to trigger the UI launch
            val showFile = appDir.resolve(".show")
            Files.writeString(showFile, "", java.nio.charset.StandardCharsets.UTF_8)

            // 4. Wait for the spawn UI message
            var uiSpawned = false
            val spawnStartTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - spawnStartTime < 10000L) {
                if (outputLines.any { it.contains("Spawning a new UI process") || it.contains("Launching UI process") }) {
                    uiSpawned = true
                    break
                }
                Thread.sleep(100)
            }

            assertTrue("UI process was not spawned in response to .show trigger. Output:\n${outputLines.joinToString("\n")}", uiSpawned)

        } finally {
            runningProcess.descendants().forEach { it.destroyForcibly() }
            runningProcess.destroyForcibly()
            readThread.interrupt()
            // Clean up temporary files
            runCatching {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
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
                dataStatus = com.weatherwidget.data.model.DataStatus.Loading,
                onUpdateLocation = {},
                onUpdateConfig = {},
                onOpenSettings = {},
                onOpenObservations = {},
            )
        }

        // Verify it shows the loading message rather than crashing
        composeTestRule.onNodeWithText("Loading…").assertExists()
    }
}
