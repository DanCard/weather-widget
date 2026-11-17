package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * plans/261113-android-data-screens-reload-after-settings-refresh.md;
 * plans/261116-comparison-screen-reloads-while-open.md (reloads while on screen).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Category(ShortDuration::class)
class StoredDataSeenTest {
    private val version = StoredDataVersion()
    private val seen = StoredDataSeen(version)

    @Test
    fun `a screen that has not loaded yet counts as changed`() {
        assertTrue(seen.changedSinceLoad())
    }

    @Test
    fun `no write since the load means no reload`() {
        seen.markLoaded()
        assertFalse(seen.changedSinceLoad())
        assertFalse("a second resume with nothing written reloads nothing", seen.changedSinceLoad())
    }

    @Test
    fun `a write after the load - Settings refresh-all while the screen was underneath - means reload`() {
        seen.markLoaded()
        version.bump("forecast_persist source=NWS")
        version.bump("forecast_persist source=OPEN_METEO")
        assertTrue(seen.changedSinceLoad())
    }

    @Test
    fun `after reloading the screen is current again`() {
        seen.markLoaded()
        version.bump("test")
        assertTrue(seen.changedSinceLoad())
        seen.markLoaded()
        assertFalse(seen.changedSinceLoad())
    }

    @Test
    fun `a write landing during a load still reads as a change because the version is taken before the read`() {
        seen.markLoaded()            // load starts: version recorded before any row is read
        version.bump("mid_load")     // a fetch stores rows while the load is reading
        assertTrue("the rows this load shows may predate that write", seen.changedSinceLoad())
    }

    @Test
    fun `log line names the screen`() {
        assertEquals("screen=history reason=stored_data_changed", StoredDataSeen.logMessage("history"))
        assertEquals("SCREEN_RELOAD", StoredDataSeen.LOG_TAG)
    }

    /** Collects [StoredDataSeen.reloads] eagerly (unconfined), runs [body], then cancels; returns what was emitted. */
    private fun reloadsDuring(body: () -> Unit): List<StoredDataSeen.Trigger> {
        val got = mutableListOf<StoredDataSeen.Trigger>()
        runTest(UnconfinedTestDispatcher()) {
            val job = launch { seen.reloads().collect { got += it; println("reload trigger=${it.logValue} version=${version.current()}") } }
            body()
            job.cancel()
        }
        return got
    }

    @Test
    fun `reloads - nothing before the first load, which the screen runs itself`() {
        version.bump("before_open")
        assertEquals(emptyList<StoredDataSeen.Trigger>(), reloadsDuring { version.bump("still_not_loaded") })
    }

    @Test
    fun `reloads - shown with nothing written emits nothing`() {
        seen.markLoaded()
        assertEquals(emptyList<StoredDataSeen.Trigger>(), reloadsDuring { })
    }

    @Test
    fun `reloads - a write while away emits RESUME at once when the screen shows again`() {
        seen.markLoaded()
        version.bump("settings_refresh")
        assertEquals(listOf(StoredDataSeen.Trigger.RESUME), reloadsDuring { })
    }

    @Test
    fun `reloads - Fold 4 2026-11-16, a source landing while on screen emits LIVE`() {
        seen.markLoaded() // the return from Settings: 15:55:45.541
        val got = reloadsDuring {
            version.bump("forecast_persist source=GOOGLE_WEATHER") // 15:55:46
        }
        assertEquals("the old code only reloaded on return, so this was empty", listOf(StoredDataSeen.Trigger.LIVE), got)
    }

    @Test
    fun `reloads - not debounced, each write the screen has not loaded at emits`() {
        seen.markLoaded()
        val got = reloadsDuring {
            version.bump("forecast_persist source=GOOGLE_WEATHER")
            version.bump("forecast_persist source=OPEN_METEO")
        }
        assertEquals(listOf(StoredDataSeen.Trigger.LIVE, StoredDataSeen.Trigger.LIVE), got)
    }

    @Test
    fun `reloads - a write the screen already loaded at, e g its own refresh button, does not emit`() {
        seen.markLoaded()
        val got = mutableListOf<StoredDataSeen.Trigger>()
        runTest(StandardTestDispatcher()) {
            val job = launch { seen.reloads().collect { got += it } }
            runCurrent() // subscribed; nothing written yet
            version.bump("forecast_persist source=NWS")
            seen.markLoaded() // fetchViewedSource reloaded on its own before the collector ran
            runCurrent()
            job.cancel()
        }
        assertEquals("a write already shown must not reload again", emptyList<StoredDataSeen.Trigger>(), got)
    }

    @Test
    fun `log line names the trigger`() {
        assertEquals(
            "screen=history reason=stored_data_changed trigger=live",
            StoredDataSeen.logMessage("history", StoredDataSeen.Trigger.LIVE),
        )
    }
}
