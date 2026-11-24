package com.weatherwidget.desktop

import com.weatherwidget.shared.util.SettingsRefreshAllSources
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** The requests Settings → Refresh data and the command line both send (plans/261124-desktop-cli-refresh-hook.md). */
@Category(ShortDuration::class)
class DesktopRefreshRequestsTest {
    private val config = DesktopConfig(
        lat = 37.42, lon = -122.08, label = "Test", dateOffset = 0,
        settings = DesktopSettings(weatherSource = "NWS", visibleSources = listOf("NWS", "OPEN_METEO", "SILURIAN")),
    )

    @Test
    fun `a stale displayed source is fetched with the observation window, then the other stale ones`() {
        val partition = SettingsRefreshAllSources.Partition(fetch = listOf("NWS", "OPEN_METEO"), fresh = mapOf("SILURIAN" to 60_000L))
        assertEquals(
            listOf(
                FetchRequest(
                    FetchRequest.Kind.FORECAST, "NWS", 37.42, -122.08, "settings", "user_refresh:settings",
                    userRefresh = true, observationWindow = true,
                ),
                FetchRequest(
                    FetchRequest.Kind.OTHER_SOURCES, "NWS", 37.42, -122.08, "settings", "user_refresh:settings",
                    userRefresh = true, sourceIds = listOf("OPEN_METEO"),
                ),
            ),
            DesktopRefreshRequests.settingsRefresh(config, partition, "settings"),
        )
    }

    @Test
    fun `a fresh displayed source sends only the other sources, and all fresh sends nothing`() {
        val othersOnly = DesktopRefreshRequests.settingsRefresh(
            config, SettingsRefreshAllSources.Partition(listOf("SILURIAN"), mapOf("NWS" to 1L)), "cli",
        )
        assertEquals(listOf(FetchRequest.Kind.OTHER_SOURCES), othersOnly.map { it.kind })
        assertEquals(listOf("SILURIAN"), othersOnly.single().sourceIds)
        assertEquals(
            emptyList<FetchRequest>(),
            DesktopRefreshRequests.settingsRefresh(config, SettingsRefreshAllSources.Partition(emptyList(), mapOf("NWS" to 1L)), "cli"),
        )
    }

    @Test
    fun `a full sync forces the displayed source and every usable other source`() {
        val requests = DesktopRefreshRequests.fullSync(config, "cli")
        assertEquals(listOf(FetchRequest.Kind.FORECAST, FetchRequest.Kind.OTHER_SOURCES), requests.map { it.kind })
        assertEquals("null = every usable source", null, requests[1].sourceIds)
        assertEquals(setOf("debug_full_sync:cli"), requests.map { it.reason }.toSet())
    }
}
