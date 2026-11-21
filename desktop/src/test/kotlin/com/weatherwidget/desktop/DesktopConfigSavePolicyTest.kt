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
    fun `location picker save outside NWS coverage rewrites no source setting`() {
        // Coverage is derived, never written (2026-09-29): the enabled list and the stored choice
        // survive a trip abroad; only what is displayed changes.
        val persisted = config(source = "NWS")
        val lviv = ResolvedLocation(lat = 49.8419, lon = 24.0316, label = "Lviv", source = "Nominatim").toConfig()

        val result = resolveDesktopConfigSave(persisted, lviv, source = "location-picker").config

        assertEquals("NWS", result.settings.weatherSource)
        assertEquals(persisted.settings.visibleSources, result.settings.visibleSources)
        assertEquals("OPEN_METEO", result.displaySource)
        assertEquals(persisted.settings.visibleSources.filter { it != "NWS" }, result.effectiveSources)
        // Non-source settings still come from the persisted config, not the picker's defaults.
        assertEquals(persisted.settings.narrowZoomSpanHours, result.settings.narrowZoomSpanHours)
    }

    @Test
    fun `location picker round trip out of and back into coverage shows NWS again`() {
        val home = config(source = "NWS")
        val lviv = ResolvedLocation(lat = 49.8419, lon = 24.0316, label = "Lviv", source = "Nominatim").toConfig()
        val away = resolveDesktopConfigSave(home, lviv, source = "location-picker").config
        assertEquals("OPEN_METEO", away.displaySource)

        val austin = ResolvedLocation(lat = 30.2672, lon = -97.7431, label = "Austin", source = "Nominatim").toConfig()
        val back = resolveDesktopConfigSave(away, austin, source = "location-picker").config

        assertEquals(home.settings.visibleSources, back.settings.visibleSources)
        assertEquals("NWS", back.displaySource)
    }

    @Test
    fun `an NWS untick in settings survives a round trip`() {
        val home = config(source = "OPEN_METEO")
        val unticked = home.copy(settings = home.settings.copy(visibleSources = listOf("OPEN_METEO", "SILURIAN")))
        val edited = resolveDesktopConfigSave(home, unticked, source = "settings").config

        val lviv = ResolvedLocation(lat = 49.8419, lon = 24.0316, label = "Lviv", source = "Nominatim").toConfig()
        val austin = ResolvedLocation(lat = 30.2672, lon = -97.7431, label = "Austin", source = "Nominatim").toConfig()
        val away = resolveDesktopConfigSave(edited, lviv, source = "location-picker").config
        val back = resolveDesktopConfigSave(away, austin, source = "location-picker").config

        assertEquals(listOf("OPEN_METEO", "SILURIAN"), back.settings.visibleSources)
        assertEquals(listOf("OPEN_METEO", "SILURIAN"), back.effectiveSources)
    }

    @Test
    fun `location picker save inside NWS coverage keeps the user's selected source`() {
        // The picker used to force NWS in the US / Open-Meteo abroad; a location no longer
        // rewrites the user's choice.
        val persisted = config(source = "OPEN_METEO")
        val austin = ResolvedLocation(lat = 30.2672, lon = -97.7431, label = "Austin", source = "Nominatim").toConfig()

        val result = resolveDesktopConfigSave(persisted, austin, source = "location-picker")

        assertEquals("OPEN_METEO", result.config.settings.weatherSource)
        assertEquals(persisted.settings.visibleSources, result.config.settings.visibleSources)
    }

    @Test
    fun `nws re-enable migration restores NWS once`() {
        val legacy = DesktopSettings(visibleSources = listOf("OPEN_METEO", "SILURIAN"))
        val migrated = legacy.withNwsReenabledOnce()
        assertEquals(listOf("NWS", "OPEN_METEO", "SILURIAN"), migrated.visibleSources)

        val untickedAfter = migrated.copy(visibleSources = listOf("OPEN_METEO"))
        assertEquals(untickedAfter, untickedAfter.withNwsReenabledOnce())
    }

    @Test
    fun `location picker save preserves and updates recent locations`() {
        val kyiv = com.weatherwidget.data.model.RecentLocation(50.45, 30.52, "Kyiv")
        val persisted = config(source = "NWS").copy(recentLocations = listOf(kyiv))
        val lviv = com.weatherwidget.data.model.RecentLocation(49.84, 24.03, "Lviv")
        val updatedRecents = com.weatherwidget.shared.util.RecentLocationsHelper.addRecent(persisted.recentLocations, lviv)
        val lvivConfig = ResolvedLocation(lat = 49.84, lon = 24.03, label = "Lviv", source = "Nominatim")
            .toConfig()
            .copy(recentLocations = updatedRecents)

        val result = resolveDesktopConfigSave(persisted, lvivConfig, source = "location-picker")
        assertEquals(2, result.config.recentLocations.size)
        assertEquals("Lviv", result.config.recentLocations[0].label)
        assertEquals("Kyiv", result.config.recentLocations[1].label)
    }

    @Test
    fun `comparison source order save updates visibleSources while merging away stale other settings`() {
        val persisted = config(source = "NWS", narrowSpan = 4)
        val reordered = listOf("SILURIAN", "OPEN_METEO", "NWS")
        val staleDraft = config(source = "OPEN_METEO", narrowSpan = 8).copy(
            settings = persisted.settings.copy(
                weatherSource = "OPEN_METEO",
                narrowZoomSpanHours = 8,
                visibleSources = reordered,
            ),
        )

        val result = resolveDesktopConfigSave(persisted, staleDraft, source = ConfigSaveSource.COMPARISON_SOURCE_ORDER)

        assertEquals(reordered, result.config.settings.visibleSources)
        assertEquals("NWS", result.config.settings.weatherSource)
        assertEquals(4, result.config.settings.narrowZoomSpanHours)
        assertEquals(listOf("visibleSources: [NWS, OPEN_METEO, SILURIAN] -> $reordered"), result.settingsChanges)
        assertEquals(2, result.mergedAwaySettings.size)
    }

    @Test
    fun `comparison window save still merges away stale visibleSources`() {
        val persisted = config(source = "NWS", narrowSpan = 4)
        val staleDraft = persisted.copy(
            sourceCompareSort = "DAY_RAIN:desc",
            settings = persisted.settings.copy(visibleSources = listOf("SILURIAN", "NWS", "OPEN_METEO")),
        )

        val result = resolveDesktopConfigSave(persisted, staleDraft, source = ConfigSaveSource.COMPARISON_WINDOW)

        assertEquals(persisted.settings.visibleSources, result.config.settings.visibleSources)
        assertEquals("DAY_RAIN:desc", result.config.sourceCompareSort)
    }

    /**
     * 2026-11-21: moving Mountain View -> Scottsdale wiped the popup geometry, zoom, view mode and the
     * observations window bounds — the picker's draft is `ResolvedLocation.toConfig()`, all defaults,
     * and only `settings` was merged back. Fails on the old merge (every assertion below but lat/lon).
     * plans/261121-desktop-location-change-freshness-and-config-clobber.md
     */
    @Test
    fun `location picker save changes the location only and keeps window state`() {
        val persisted = config(source = "GOOGLE_WEATHER", zoomFactor = 0.2555302f).copy(
            viewMode = com.weatherwidget.widget.ViewMode.TEMPERATURE,
            windowX = 44f, windowY = 1218f, windowWidth = 1170f, windowHeight = 926f,
            obsWindowX = 1687f, obsWindowY = 730f, obsWindowWidth = 500f, obsWindowHeight = 1013f,
            historyWindowX = 1194f,
            sourceCompareSort = "DAY_RAIN:desc",
        )
        val scottsdale = ResolvedLocation(lat = 33.6451828, lon = -111.9112613, label = "Scottsdale", source = "Google")
            .toConfig()
            .copy(recentLocations = listOf(com.weatherwidget.data.model.RecentLocation(33.6451828, -111.9112613, "Scottsdale")))

        val result = resolveDesktopConfigSave(persisted, scottsdale, source = DesktopLocationChangeFeedback.PICKER_SOURCE)
        val saved = result.config
        println("picker save top-level changes: ${result.topLevelChanges}")

        assertEquals(33.6451828, saved.lat, 0.0)
        assertEquals(-111.9112613, saved.lon, 0.0)
        assertEquals("Scottsdale", saved.label)
        assertEquals(1, saved.recentLocations.size)
        assertEquals(persisted.copy(lat = saved.lat, lon = saved.lon, label = saved.label, recentLocations = saved.recentLocations), saved)
        assertEquals(
            "only the location fields change",
            listOf("label", "lat", "lon", "recentLocations.size"),
            result.topLevelChanges.map { it.name },
        )
        assertEquals(emptyList<ConfigFieldChange>(), result.topLevelChanges.lostFields())
    }

    @Test
    fun `a whole-object save from defaults is flagged as lost fields`() {
        val persisted = config(zoomFactor = 0.2555302f).copy(
            viewMode = com.weatherwidget.widget.ViewMode.TEMPERATURE,
            windowX = 44f, windowY = 1218f,
            dateOffset = 2,
        )
        // What the old picker path persisted: the draft's defaults for every non-settings field.
        val defaults = DesktopConfig(lat = 33.6, lon = -111.9, label = "Scottsdale", settings = persisted.settings)

        val changes = defaults.topLevelDiffFrom(persisted)
        val lost = changes.lostFields().map { it.name }
        println("whole-object reset: changes=$changes lost=$lost")

        assertEquals(listOf("dateOffset", "viewMode", "windowX", "windowY", "zoomFactor"), lost.sorted())
    }

    @Test
    fun `returning one field to its default is not a loss`() {
        // Navigating back to today resets dateOffset; that is normal, not a clobber.
        val persisted = config().copy(dateOffset = 3, windowX = 44f)
        val changes = persisted.copy(dateOffset = 0).topLevelDiffFrom(persisted)
        assertEquals(listOf("dateOffset"), changes.map { it.name })
        assertEquals(emptyList<ConfigFieldChange>(), changes.lostFields())
    }

    @Test
    fun `config load diff warns only when the re-read lost a field`() {
        val held = config().copy(windowX = 44f, windowWidth = 1170f)
        assertNull(configLoadDiff("daemon", held, held))
        assertNull("first load has nothing to compare", configLoadDiff("daemon", null, held))

        val moved = configLoadDiff("daemon", held, held.copy(windowX = 60f))!!
        assertEquals(false, moved.warn)
        assertEquals("reader=daemon fields: windowX: 44.0 -> 60.0", moved.message)

        val clobbered = configLoadDiff("daemon", held, held.copy(windowX = null, windowWidth = null))!!
        println("clobbered load diff: ${clobbered.message}")
        assertEquals(true, clobbered.warn)
        assertEquals(true, clobbered.message.startsWith("reader=daemon lost=windowWidth,windowX "))
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
