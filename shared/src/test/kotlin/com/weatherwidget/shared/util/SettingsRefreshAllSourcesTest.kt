package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class SettingsRefreshAllSourcesTest {
    private class FakeHost(private val failing: Set<String> = emptySet(), private val cancelOn: String? = null) {
        val refreshed = mutableListOf<String>()
        val logs = mutableListOf<String>()

        suspend fun refresh(sourceId: String) {
            if (sourceId == cancelOn) throw CancellationException("closed")
            if (sourceId in failing) throw IllegalStateException("HTTP 500")
            refreshed += sourceId
        }

        fun log(tag: String, message: String, level: String) {
            logs += "$tag $level $message"
        }
    }

    private suspend fun refreshOthers(displayedId: String, host: FakeHost) =
        SettingsRefreshAllSources.refreshOthers(displayedId, usable, host::refresh, host::log)

    private val usable = listOf("GOOGLE_WEATHER", "OPEN_METEO", "NWS", "TOMORROW_IO", "SILURIAN")

    @Test
    fun `order - displayed first, then the rest in usable order`() {
        assertEquals(
            listOf("NWS", "GOOGLE_WEATHER", "OPEN_METEO", "TOMORROW_IO", "SILURIAN"),
            SettingsRefreshAllSources.order("NWS", usable),
        )
        assertEquals("displayed not usable still leads", listOf("WEATHER_API", "NWS"), SettingsRefreshAllSources.order("WEATHER_API", listOf("NWS")))
    }

    @Test
    fun `others - every non-displayed source, the displayed one is not fetched twice`() = runBlocking {
        val host = FakeHost()
        val outcome = refreshOthers("NWS", host)
        // Desktop, 2026-10-29: Settings → Refresh data fetched NWS alone.
        assertEquals(listOf("GOOGLE_WEATHER", "OPEN_METEO", "TOMORROW_IO", "SILURIAN"), host.refreshed)
        assertEquals(host.refreshed, outcome.refreshed)
        assertTrue(outcome.failed.isEmpty())
        assertEquals("SETTINGS_REFRESH INFO start displayed=NWS others=GOOGLE_WEATHER,OPEN_METEO,TOMORROW_IO,SILURIAN", host.logs.first())
    }

    @Test
    fun `others - one source failing does not stop the rest`() = runBlocking {
        val host = FakeHost(failing = setOf("OPEN_METEO"))
        val outcome = refreshOthers("NWS", host)
        assertEquals(listOf("GOOGLE_WEATHER", "TOMORROW_IO", "SILURIAN"), host.refreshed)
        assertEquals(setOf("OPEN_METEO"), outcome.failed.keys)
        assertTrue(host.logs.any { it == "SETTINGS_REFRESH WARN source=OPEN_METEO failed IllegalStateException: HTTP 500" })
        assertTrue(host.logs.last().startsWith("SETTINGS_REFRESH WARN done refreshed=GOOGLE_WEATHER,TOMORROW_IO,SILURIAN failed=OPEN_METEO"))
    }

    @Test
    fun `others - cancellation propagates`() = runBlocking {
        val host = FakeHost(cancelOn = "OPEN_METEO")
        try {
            refreshOthers("NWS", host)
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(listOf("GOOGLE_WEATHER"), host.refreshed)
    }

    @Test
    fun `view gate - bypassed only by an untargeted all-sources refresh`() {
        assertTrue(SettingsRefreshAllSources.bypassesViewGate(allSources = true, targetSourceId = null))
        assertFalse(SettingsRefreshAllSources.bypassesViewGate(allSources = true, targetSourceId = "NWS"))
        assertFalse(SettingsRefreshAllSources.bypassesViewGate(allSources = false, targetSourceId = null))
    }
}
