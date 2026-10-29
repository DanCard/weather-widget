package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.sourceview.SourceSwitchRunner
import com.weatherwidget.test.category.MediumDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The 2026-10-29 stall, reproduced: another process holds the SQLite write lock while the user switches
 * source. The switch (config save + daemon trigger) must not wait for it; the app_logs rows and the
 * view tally land after the lock is released. DesktopSourceSwitch + SourceSwitchRunner +
 * DesktopSourceViews + DesktopWeatherDao on a real WAL database.
 * plans/261029-shared-source-switch-runner.md
 */
@Category(MediumDuration::class)
class DesktopSourceSwitchLockIntegrationTest {
    private lateinit var tempDir: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var deferredScope: CoroutineScope
    private val viewsExecutor = Executors.newSingleThreadExecutor()

    private val config = DesktopConfig(
        lat = 37.42,
        lon = -122.08,
        label = "Mountain View",
        settings = DesktopSettings(visibleSources = listOf("NWS", "SILURIAN", "GOOGLE_WEATHER"), weatherSource = "SILURIAN"),
    )

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("source-switch-lock")
        db = DesktopWeatherDatabase(tempDir.resolve("weather.db")).apply { initialize() }
        dao = DesktopWeatherDao(db)
        deferredScope = CoroutineScope(Job() + Dispatchers.IO)
        DesktopSourceViews.install(dao, viewsExecutor)
        DesktopSourceSwitch.install(dao, deferredScope)
    }

    @After
    fun tearDown() {
        deferredScope.cancel()
        DesktopSourceSwitch.install(null)
        DesktopSourceViews.install(null)
        viewsExecutor.shutdownNow()
        tempDir.toFile().deleteRecursively()
    }

    /** Holds the write lock from another connection until [release] counts down. */
    private fun holdWriteLock(release: CountDownLatch): Thread {
        val locked = CountDownLatch(1)
        val holder = thread(name = "other-process-writer") {
            db.getConnection().use { conn: Connection ->
                conn.autoCommit = false
                locked.countDown()
                release.await(10, TimeUnit.SECONDS)
                conn.commit()
            }
        }
        assertTrue(locked.await(5, TimeUnit.SECONDS))
        return holder
    }

    private data class LogRow(val message: String, val level: String)

    private fun rows(tag: String): List<LogRow> = db.getConnection().use { conn ->
        conn.prepareStatement("SELECT message, level FROM app_logs WHERE tag = ? ORDER BY id").use { st ->
            st.setString(1, tag)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(LogRow(rs.getString(1), rs.getString(2))) } }
        }
    }

    @Test
    fun `a source switch does not wait for another process's write lock`() = runBlocking {
        val release = CountDownLatch(1)
        val holder = holdWriteLock(release)
        var saved: DesktopConfig? = null

        val startNs = System.nanoTime()
        cycleDisplaySource(config) { saved = it }
        val switchMs = (System.nanoTime() - startNs) / 1_000_000

        assertEquals("GOOGLE_WEATHER", saved!!.settings.weatherSource)
        assertTrue("switch took ${switchMs}ms behind a held lock", switchMs < 500)
        assertTrue("nothing written while the lock is held", rows(SourceSwitchRunner.E2E_TAG).isEmpty())

        release.countDown()
        holder.join()
        withTimeout(10_000) { deferredScope.coroutineContext[Job]!!.children.forEach { it.join() } }
        viewsExecutor.submit {}.get(5, TimeUnit.SECONDS)

        val row = rows(SourceSwitchRunner.E2E_TAG).single().message
        assertTrue(row, row.startsWith("platform=desktop from=SILURIAN to=GOOGLE_WEATHER trigger=TOGGLE phase=done"))
        assertTrue(row, "refresh=DELEGATED" in row)
        assertEquals(1, dao.sourceViewDaysSince(0L).single { it.sourceId == "GOOGLE_WEATHER" }.switches)
    }

    @Test
    fun `home button resets the source through the same runner`() = runBlocking {
        var saved: DesktopConfig? = null
        DesktopSourceSwitch.switchTo(config, "NWS", com.weatherwidget.shared.sourceview.SourceViewTrigger.HOME) { saved = it }
        withTimeout(10_000) { deferredScope.coroutineContext[Job]!!.children.forEach { it.join() } }
        viewsExecutor.submit {}.get(5, TimeUnit.SECONDS)

        assertEquals("NWS", saved!!.settings.weatherSource)
        assertTrue(rows(SourceSwitchRunner.E2E_TAG).single().message.contains("to=NWS trigger=HOME phase=done"))
        assertEquals("HOME", dao.sourceViewDaysSince(0L).single().trigger)
    }

    @Test
    fun `a config save writes the file and signals the daemon before any app_logs row`() {
        val release = CountDownLatch(1)
        val holder = holdWriteLock(release)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val logExecutor = Executors.newSingleThreadExecutor()
        try {
            val startNs = System.nanoTime()
            commitDesktopConfigSave(
                save = { events += "save" },
                signalDaemon = { events += "signal" },
                logRows = listOf("source=popup weatherSource: SILURIAN -> GOOGLE_WEATHER" to "WARN"),
                logExecutor = logExecutor,
                log = { message, level -> dao.log("CONFIG_SAVE", message, level); events += "log" },
            )
            val commitMs = (System.nanoTime() - startNs) / 1_000_000

            assertEquals(listOf("save", "signal"), events.toList())
            assertTrue("save took ${commitMs}ms behind a held lock", commitMs < 500)

            release.countDown()
            holder.join()
            logExecutor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(listOf("save", "signal", "log"), events.toList())
            assertEquals("WARN", rows("CONFIG_SAVE").single().level)
        } finally {
            release.countDown()
            logExecutor.shutdownNow()
        }
    }
}
