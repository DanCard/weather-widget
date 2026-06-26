package com.weatherwidget.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue("tooltip explains the fix", m.contains("buildStart.sh"))
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
}
