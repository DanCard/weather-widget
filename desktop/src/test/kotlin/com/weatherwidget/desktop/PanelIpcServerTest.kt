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

    private fun testConfig() = DesktopConfig(
lat = 37.4220,
lon = -122.0841,
label = "Test",
settings = DesktopSettings(useCelsius = false),
)

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

    @Test
    fun `buildPanelMarkup with custom font sizes applies them to spans`() {
        val m = PanelIpcServer.buildPanelMarkup(
            body = "68.8°",
            color = PanelIpcServer.LIVE_COLOR,
            deltaText = "+0.9",
            tooltip = "test",
            clickCmd = "#",
            tempFontSize = 10,
            deltaFontSize = 9,
        )
        assertTrue("scaled temp font size missing", m.contains("font='Sans Bold 10'"))
        assertTrue("scaled delta font size missing", m.contains("font='Sans Bold 9'"))
    }

    @Test
    fun `resolveGenmonFontSizes scales fonts across standard resolutions`() {
        // 720p or lower clamps to minimum readable panel size
        org.junit.Assert.assertEquals(10 to 9, DisplayResolutionDetector.resolveGenmonFontSizes(480))
        org.junit.Assert.assertEquals(10 to 9, DisplayResolutionDetector.resolveGenmonFontSizes(720))

        // Intermediate resolutions
        org.junit.Assert.assertEquals(12 to 10, DisplayResolutionDetector.resolveGenmonFontSizes(900))
        org.junit.Assert.assertEquals(13 to 12, DisplayResolutionDetector.resolveGenmonFontSizes(1080))
        org.junit.Assert.assertEquals(16 to 15, DisplayResolutionDetector.resolveGenmonFontSizes(1440))

        // 4K and higher clamps to 22 / 20 baseline
        org.junit.Assert.assertEquals(22 to 20, DisplayResolutionDetector.resolveGenmonFontSizes(2160))
        org.junit.Assert.assertEquals(22 to 20, DisplayResolutionDetector.resolveGenmonFontSizes(4320))
    }

    @Test
    fun `parseXrandrHeight extracts height from connected monitor or screen line`() {
        val xrandrOutput = """
            Screen 0: minimum 320 x 200, current 1280 x 720, maximum 16384 x 16384
            HDMI-A-0 connected 1280x720+0+0 (normal left inverted right x axis y axis) 708mm x 398mm
               1280x720      60.00*
        """.trimIndent()
        org.junit.Assert.assertEquals(720, DisplayResolutionDetector.parseXrandrHeight(xrandrOutput))

        val xrandr4k = """
            Screen 0: minimum 320 x 200, current 3840 x 2160, maximum 16384 x 16384
            DP-1 connected primary 3840x2160+0+0 (normal left inverted right x axis y axis) 600mm x 340mm
        """.trimIndent()
        org.junit.Assert.assertEquals(2160, DisplayResolutionDetector.parseXrandrHeight(xrandr4k))
    }

    @Test
    fun `parseXwininfoHeight extracts height correctly`() {
        val xwininfoOutput = """
            xwininfo: Window id: 0x25c (the root window) (has no name)
              Width: 1280
              Height: 720
              Depth: 24
        """.trimIndent()
        org.junit.Assert.assertEquals(720, DisplayResolutionDetector.parseXwininfoHeight(xwininfoOutput))
    }
}
