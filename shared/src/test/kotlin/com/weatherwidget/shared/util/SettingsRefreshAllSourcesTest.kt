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
    fun `others - every failed source is reported for its retry, the others are not`() = runBlocking {
        val host = FakeHost(failing = setOf("OPEN_METEO", "SILURIAN"))
        val reported = mutableListOf<Pair<String, String?>>()
        SettingsRefreshAllSources.refreshOthers(
            "NWS", usable, host::refresh, host::log,
            onFailure = { id, e -> reported += id to e.message },
        )
        assertEquals(listOf("OPEN_METEO", "SILURIAN"), reported.map { it.first })
        assertEquals("HTTP 500", reported.first().second)
    }

    @Test
    fun `view gate - bypassed only by an untargeted all-sources refresh`() {
        assertTrue(SettingsRefreshAllSources.bypassesViewGate(allSources = true, targetSourceId = null))
        assertFalse(SettingsRefreshAllSources.bypassesViewGate(allSources = true, targetSourceId = "NWS"))
        assertFalse(SettingsRefreshAllSources.bypassesViewGate(allSources = false, targetSourceId = null))
    }

    // User, 2026-11-13: popup fetched NWS at 16:57:53, Settings → Refresh data refetched it 75 s later.
    private val now = 1_794_617_947_000L
    private val min = 60_000L

    @Test
    fun `partition - a source fetched 75 s ago is skipped, the stale ones are fetched in order`() {
        val fetched = mapOf("NWS" to now - 75_000L, "GOOGLE_WEATHER" to now - 3 * 60 * min, "OPEN_METEO" to now - 11 * min)
        val p = SettingsRefreshAllSources.partition(
            SettingsRefreshAllSources.order("NWS", usable), { fetched[it] }, { null }, now,
        )
        println("partition: ${p.logLine()}")
        assertEquals(listOf("GOOGLE_WEATHER", "OPEN_METEO", "TOMORROW_IO", "SILURIAN"), p.fetch)
        assertEquals(mapOf("NWS" to 75_000L), p.fresh)
        assertFalse(p.allFresh)
        assertEquals("partition fetch=GOOGLE_WEATHER,OPEN_METEO,TOMORROW_IO,SILURIAN skip_fresh=NWS(75s)", p.logLine())
    }

    @Test
    fun `partition - 10 minute boundary, user refresh counts, never-fetched and future times fetch`() {
        val fetched = mapOf("NWS" to now - 10 * min + 1_000L, "GOOGLE_WEATHER" to now - 10 * min, "TOMORROW_IO" to now + 5 * min)
        val userRefreshed = mapOf("OPEN_METEO" to now - 2 * min)
        val p = SettingsRefreshAllSources.partition(usable, { fetched[it] }, { userRefreshed[it] }, now)
        println("partition: ${p.logLine()}")
        assertEquals("9:59 old is fresh; a user refresh alone is fresh", setOf("NWS", "OPEN_METEO"), p.fresh.keys)
        assertEquals("exactly 10 min, future (clock change) and never-fetched are fetched", listOf("GOOGLE_WEATHER", "TOMORROW_IO", "SILURIAN"), p.fetch)
    }

    @Test
    fun `partition - every source fresh means nothing to fetch`() {
        val p = SettingsRefreshAllSources.partition(usable, { now - min }, { null }, now)
        assertTrue(p.allFresh)
        assertEquals("partition fetch=none skip_fresh=GOOGLE_WEATHER(60s),OPEN_METEO(60s),NWS(60s),TOMORROW_IO(60s),SILURIAN(60s)", p.logLine())
    }

    @Test
    fun `others - runs exactly the list it is given`() = runBlocking {
        val host = FakeHost()
        SettingsRefreshAllSources.refreshOthers("NWS", listOf("NWS", "SILURIAN"), host::refresh, host::log)
        assertEquals(listOf("SILURIAN"), host.refreshed)
    }

    @Test
    fun `all fresh notice reads Already up to date`() {
        assertEquals("Already up to date", RefreshNotice.text(RefreshNotice.NotQueued(RefreshNotice.Reason.ALL_FRESH)) { "" })
    }
}
