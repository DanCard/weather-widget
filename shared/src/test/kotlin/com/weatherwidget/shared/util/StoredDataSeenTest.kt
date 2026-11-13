package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** plans/261113-android-data-screens-reload-after-settings-refresh.md */
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
}
