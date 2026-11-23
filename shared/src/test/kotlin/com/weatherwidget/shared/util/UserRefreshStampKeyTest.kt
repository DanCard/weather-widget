package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** plans/261122-user-refresh-stamps-per-site.md */
@Category(ShortDuration::class)
class UserRefreshStampKeyTest {
    private val mountainView = 37.4166014 to -122.0888722
    private val scottsdale = 33.6451828 to -111.9112613

    private fun key(id: String, site: Pair<Double, Double>) = UserRefreshStampKey.of(id, site.first, site.second)

    @Test
    fun `jitter inside one quantized site shares a key`() {
        val jittered = mountainView.first + 0.0001 to mountainView.second - 0.0001
        println("key=${key("NWS", mountainView)} jittered=${key("NWS", jittered)}")
        assertEquals(key("NWS", mountainView), key("NWS", jittered))
    }

    @Test
    fun `different sites and sources have different keys`() {
        assertNotEquals(key("NWS", mountainView), key("NWS", scottsdale))
        assertNotEquals(key("NWS", mountainView), key("GOOGLE_WEATHER", mountainView))
        assertNotEquals(key("NWS", mountainView), key("NWS:observations", mountainView))
    }

    @Test
    fun `no site keys by id alone`() {
        assertEquals("NWS", UserRefreshStampKey.of("NWS", null, null))
    }

    /** The 2026-11-22 incident through the shared partition: a stamp at Scottsdale is no freshness at Mountain View. */
    @Test
    fun `refresh stamps at one site do not skip sources at another`() {
        val now = 1_800_000_000_000L
        val stamps = listOf("GOOGLE_WEATHER", "OPEN_METEO").associate { key(it, scottsdale) to now - 126_000L }
        fun partitionAt(site: Pair<Double, Double>) = SettingsRefreshAllSources.partition(
            listOf("NWS", "GOOGLE_WEATHER", "OPEN_METEO"),
            lastFetchMs = { if (it == "NWS" && site == mountainView) now - 18_000L else null },
            lastUserRefreshMs = { stamps[key(it, site)] },
            nowMs = now,
        )

        val atMountainView = partitionAt(mountainView)
        val atScottsdale = partitionAt(scottsdale)
        println("mountainView: ${atMountainView.logLine()}")
        println("scottsdale: ${atScottsdale.logLine()}")

        assertEquals(listOf("GOOGLE_WEATHER", "OPEN_METEO"), atMountainView.fetch)
        assertEquals(setOf("NWS"), atMountainView.fresh.keys)
        assertEquals(listOf("NWS"), atScottsdale.fetch)
    }
}
