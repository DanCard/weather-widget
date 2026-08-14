package com.weatherwidget.desktop

import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class PanelIpcServerTest {

    private fun markup(deltaText: String?) = PanelIpcServer.buildPanelMarkup(
        body = "72.5°",
        color = PanelIpcServer.LIVE_COLOR,
        deltaText = deltaText,
        tooltip = "Weather Widget — measured just now",
        clickCmd = "touch /tmp/.show",
    )

    @Test
    fun `includes orange delta span when delta present`() {
        val m = markup("+1.2")
        assertTrue("delta value missing", m.contains("+1.2"))
        assertTrue("header color missing", m.contains(PanelIpcServer.DELTA_COLOR))
    }

    @Test
    fun `omits delta span when delta null`() {
        val m = markup(null)
        assertFalse("should not emit delta color", m.contains(PanelIpcServer.DELTA_COLOR))
        // Exactly one span (the temperature) — no second span appended.
        assertFalse("should not contain a second span", m.contains("</span><span"))
    }

    @Test
    fun `missing-launcher markup warns, keeps the temp, and rewires the dead click`() {
        val m = PanelIpcServer.missingLauncherMarkup("72.5°")
        assertTrue("temperature retained", m.contains("72.5°"))
        assertTrue("warning glyph present", m.contains("⚠"))
        assertTrue("warn color present", m.contains(PanelIpcServer.WARN_COLOR))
        assertTrue("tooltip explains the fix", m.contains("buildStart-desktop.sh"))
        // The click must no longer touch .show (the daemon can't spawn the UI); it notifies instead.
        assertTrue("click notifies the user", m.contains("notify-send"))
        assertFalse("dead .show click removed", m.contains("touch "))
    }

    @Test
    fun `always emits temp span tooltip and txtclick`() {
        for (delta in listOf("+1.2", null)) {
            val m = markup(delta)
            assertTrue(m.contains("72.5°"))
            assertTrue(m.contains(PanelIpcServer.LIVE_COLOR))
            assertTrue(m.contains("<tool>Weather Widget — measured just now</tool>"))
            assertTrue(m.contains("<txtclick>touch /tmp/.show</txtclick>"))
        }
    }

    private fun testConfig() = DesktopConfig(lat = 37.4220, lon = -122.0841, label = "Test", useCelsius = false)

    private fun server() = PanelIpcServer(
        appDataDir = Files.createTempDirectory("panel-ipc-test"),
        markupProvider = { "" },
    )

    @Test
    fun `generateMarkup reports measured for a fresh observation`() {
        val m = server().generateMarkup(
            observedAtMs = System.currentTimeMillis() - 60_000L,
            currentTemp = 72.5f,
            deltaFromYesterday = 1.2f,
            dataStatus = DataStatus.Live(System.currentTimeMillis()),
            config = testConfig(),
        )
        assertTrue("temp body missing", m.contains("72.5°"))
        assertTrue("fresh obs must read measured", m.contains("measured just now"))
        assertTrue("delta span missing", m.contains("+1.2"))
    }

    @Test
    fun `generateMarkup reports interpolated for an old observation`() {
        val m = server().generateMarkup(
            observedAtMs = System.currentTimeMillis() - (31 * 60 * 1000L),
            currentTemp = 72.5f,
            deltaFromYesterday = null,
            dataStatus = DataStatus.Live(System.currentTimeMillis()),
            config = testConfig(),
        )
        assertTrue("stale obs must read interpolated", m.contains("interpolated just now"))
    }

    @Test
    fun `generateMarkup reports no data when nothing has been published`() {
        val m = server().generateMarkup(
            observedAtMs = null,
            currentTemp = null,
            deltaFromYesterday = null,
            dataStatus = DataStatus.Live(System.currentTimeMillis()),
            config = testConfig(),
        )
        assertTrue("empty body must be --", m.contains("--"))
        assertTrue("empty state must read no data", m.contains("no data just now"))
    }
}
