package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.sourceview.SourceSwitchRunner
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import com.weatherwidget.test.category.MediumDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A reload's breadcrumb must not wait on another process's write lock: [DesktopAppLogQueue] returns at
 * once and writes the row, stamped when it was logged, after the lock is released. And the desktop
 * `phase=data_ready` line times a switch until the UI holds the new source's data.
 * plans/261029-shared-source-switch-runner.md
 */
@Category(MediumDuration::class)
class DesktopAppLogQueueTest {
    private lateinit var dir: Path
    private lateinit var db: DesktopWeatherDatabase
    private var clockMs = 0L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("app-log-queue")
        db = DesktopWeatherDatabase(dir.resolve("weather.db")).apply { initialize() }
        DesktopAppLogQueue.install(DesktopWeatherDao(db))
    }

    @After
    fun tearDown() {
        DesktopAppLogQueue.drain()
        DesktopAppLogQueue.install(null)
        DesktopSourceSwitch.install(null)
        dir.toFile().deleteRecursively()
    }

    private fun rows(): List<Triple<Long, String, String>> = db.getConnection().use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT timestamp, tag, message FROM app_logs ORDER BY id").use { rs ->
                buildList { while (rs.next()) add(Triple(rs.getLong(1), rs.getString(2), rs.getString(3))) }
            }
        }
    }

    @Test
    fun `a queued row does not wait for a held lock and keeps its time and order`() {
        val release = CountDownLatch(1)
        val locked = CountDownLatch(1)
        val holder = thread(name = "other-process-writer") {
            db.getConnection().use { conn ->
                conn.autoCommit = false
                locked.countDown()
                release.await(10, TimeUnit.SECONDS)
                conn.commit()
            }
        }
        assertTrue(locked.await(5, TimeUnit.SECONDS))

        val beforeMs = System.currentTimeMillis()
        val startNs = System.nanoTime()
        DesktopAppLogQueue.log("CURR_TEMP_RESULT", "first", "DEBUG")
        DesktopAppLogQueue.log("CURRENT_STATUS_PUBLISH", "second", "INFO")
        val callMs = (System.nanoTime() - startNs) / 1_000_000
        Thread.sleep(300)
        release.countDown()
        holder.join()
        DesktopAppLogQueue.drain()

        assertTrue("log() took ${callMs}ms behind a held lock", callMs < 100)
        val written = rows()
        assertEquals(listOf("first", "second"), written.map { it.third })
        assertTrue("stamped when logged, not when written: ${written[0].first - beforeMs}ms", written[0].first - beforeMs < 250)
    }

    @Test
    fun `data_ready is logged once, for the switched-to source, with the time since the switch`() {
        DesktopSourceSwitch.install(null, CoroutineScope(Dispatchers.Unconfined)) { clockMs }
        val config = DesktopConfig(
            lat = 37.42,
            lon = -122.08,
            label = "Mountain View",
            settings = DesktopSettings(visibleSources = listOf("NWS", "SILURIAN", "GOOGLE_WEATHER"), weatherSource = "SILURIAN"),
        )
        clockMs = 1_000
        DesktopSourceSwitch.switchTo(config, "GOOGLE_WEATHER", SourceViewTrigger.TOGGLE) {}

        clockMs = 1_250
        DesktopSourceSwitch.onDataShown("SILURIAN") // a late reload of the old source: not the switch
        clockMs = 1_400
        DesktopSourceSwitch.onDataShown(WeatherSource.GOOGLE_WEATHER.id)
        clockMs = 9_000
        DesktopSourceSwitch.onDataShown(WeatherSource.GOOGLE_WEATHER.id) // a later reload: already reported
        DesktopAppLogQueue.drain()

        val ready = rows().filter { it.second == SourceSwitchRunner.E2E_TAG }.map { it.third }
        assertEquals(
            listOf("platform=desktop from=SILURIAN to=GOOGLE_WEATHER trigger=TOGGLE phase=data_ready sinceSwitchMs=400"),
            ready,
        )
    }
}
