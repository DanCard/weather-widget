package com.weatherwidget.desktop

import com.weatherwidget.shared.graph.ZoomStage
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopConfigSavePolicyTest {
    @Test
    fun `popup save keeps persisted settings except active source`() {
        val persisted = config(source = "NWS", narrowSpan = 4, offset = 0)
        val stalePopup = config(source = "OPEN_METEO", narrowSpan = 8, offset = 7)

        val result = resolveDesktopConfigSave(persisted, stalePopup, source = "popup")

        assertEquals("OPEN_METEO", result.config.settings.weatherSource)
        assertEquals(4, result.config.settings.narrowZoomSpanHours)
        assertEquals(7, result.config.hourlyOffset)
        assertEquals(listOf("weatherSource: NWS -> OPEN_METEO"), result.settingsChanges)
        assertEquals(listOf("narrowZoomSpanHours: 4 -> 8"), result.mergedAwaySettings)
    }

    @Test
    fun `unprivileged writer cannot change settings`() {
        val persisted = config(source = "NWS", narrowSpan = 4)
        val staleHistory = config(source = "OPEN_METEO", narrowSpan = 8, offset = -24)

        val result = resolveDesktopConfigSave(persisted, staleHistory, source = "history")

        assertEquals(persisted.settings, result.config.settings)
        assertEquals(-24, result.config.hourlyOffset)
        assertEquals(emptyList<String>(), result.settingsChanges)
        assertEquals(2, result.mergedAwaySettings.size)
        assertNull(result.zoomFactorBeforeResnap)
    }

    @Test
    fun `settings save reports narrow zoom resnap`() {
        val oldSpan = 5
        val persisted = config(
            narrowSpan = oldSpan,
            zoomFactor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, oldSpan),
        )
        val draft = persisted.copy(settings = persisted.settings.copy(narrowZoomSpanHours = 8))

        val result = resolveDesktopConfigSave(persisted, draft, source = "settings")

        assertNotNull(result.zoomFactorBeforeResnap)
        assertEquals(DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, 8), result.config.zoomFactor)
        assertEquals(listOf("narrowZoomSpanHours: 5 -> 8"), result.settingsChanges)
    }

    private fun config(
        source: String = "NWS",
        narrowSpan: Int = 6,
        offset: Int = 0,
        zoomFactor: Float = DesktopGraphUtils.DEFAULT_ZOOM_FACTOR,
    ) = DesktopConfig(
        lat = 37.42,
        lon = -122.08,
        label = "Test",
        hourlyOffset = offset,
        zoomFactor = zoomFactor,
        settings = DesktopSettings(
            weatherSource = source,
            narrowZoomSpanHours = narrowSpan,
        ),
    )
}
