package com.weatherwidget.desktop

import com.weatherwidget.data.model.ResolvedLocation
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

    @Test
    fun `location picker save outside NWS coverage retires NWS from the source cycle`() {
        val persisted = config(source = "NWS")
        val lviv = ResolvedLocation(lat = 49.8419, lon = 24.0316, label = "Lviv", source = "Nominatim").toConfig()

        val result = resolveDesktopConfigSave(persisted, lviv, source = "location-picker")

        assertEquals("OPEN_METEO", result.config.settings.weatherSource)
        assertEquals(
            persisted.settings.visibleSources.filter { it != "NWS" },
            result.config.settings.visibleSources,
        )
        // Non-source settings still come from the persisted config, not the picker's defaults.
        assertEquals(persisted.settings.narrowZoomSpanHours, result.config.settings.narrowZoomSpanHours)
    }

    @Test
    fun `location picker round trip out of and back into coverage restores NWS`() {
        val home = config(source = "NWS")
        val lviv = ResolvedLocation(lat = 49.8419, lon = 24.0316, label = "Lviv", source = "Nominatim").toConfig()
        val away = resolveDesktopConfigSave(home, lviv, source = "location-picker").config
        assertEquals(true, away.settings.nwsAutoRetired)

        val austin = ResolvedLocation(lat = 30.2672, lon = -97.7431, label = "Austin", source = "Nominatim").toConfig()
        val back = resolveDesktopConfigSave(away, austin, source = "location-picker").config

        assertEquals(home.settings.visibleSources, back.settings.visibleSources)
        assertEquals("NWS", back.settings.weatherSource)
        assertEquals(false, back.settings.nwsAutoRetired)
    }

    @Test
    fun `a settings edit of the source list makes the NWS state the user's own`() {
        val away = config(source = "OPEN_METEO").let {
            it.copy(settings = it.settings.copy(visibleSources = listOf("OPEN_METEO", "SILURIAN"), nwsAutoRetired = true))
        }
        val edited = away.copy(settings = away.settings.copy(visibleSources = listOf("SILURIAN", "OPEN_METEO")))

        val result = resolveDesktopConfigSave(away, edited, source = "settings").config

        assertEquals(false, result.settings.nwsAutoRetired)
        val austin = ResolvedLocation(lat = 30.2672, lon = -97.7431, label = "Austin", source = "Nominatim").toConfig()
        val back = resolveDesktopConfigSave(result, austin, source = "location-picker").config
        assertEquals(listOf("SILURIAN", "OPEN_METEO"), back.settings.visibleSources)
    }

    @Test
    fun `location picker save inside NWS coverage keeps the source cycle`() {
        val persisted = config(source = "OPEN_METEO")
        val austin = ResolvedLocation(lat = 30.2672, lon = -97.7431, label = "Austin", source = "Nominatim").toConfig()

        val result = resolveDesktopConfigSave(persisted, austin, source = "location-picker")

        assertEquals("NWS", result.config.settings.weatherSource)
        assertEquals(persisted.settings.visibleSources, result.config.settings.visibleSources)
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
