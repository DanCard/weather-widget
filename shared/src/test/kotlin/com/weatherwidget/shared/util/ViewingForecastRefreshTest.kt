package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ViewingForecastRefreshTest {
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val now = 1_000 * hour

    private class FakeHost(
        override val isCharging: Boolean = false,
        override val batteryLevel: Int = 85,
        val fetched: MutableMap<String, Long?>,
        val requested: MutableMap<String, Long> = mutableMapOf(),
    ) : ViewingForecastRefresh.Host {
        val fetches = mutableListOf<String>()
        override fun viewedSourceIds() = fetched.keys.toList()
        override fun lastFetchMs(sourceId: String) = fetched[sourceId]
        override fun lastRequestMs(sourceId: String) = requested[sourceId]
        override fun markRequested(sourceId: String, atMs: Long) { requested[sourceId] = atMs }
        override suspend fun fetch(sourceId: String) { fetches += sourceId }
        override suspend fun log(tag: String, message: String) = Unit
    }

    private fun run(host: FakeHost, at: Long = now) = runBlocking { ViewingForecastRefresh.run(host, "test", at) }

    @Test
    fun `unplugged at high battery a viewed source fetches at 2 h, not before`() {
        val host = FakeHost(fetched = mutableMapOf("NWS" to now - 2 * hour, "GOOGLE" to now - 2 * hour + minute))
        assertEquals(listOf("NWS"), run(host))
        assertEquals(listOf("NWS"), host.fetches)
    }

    @Test
    fun `a charger counts as high battery`() {
        assertEquals(listOf("NWS"), run(FakeHost(isCharging = true, batteryLevel = 30, fetched = mutableMapOf("NWS" to now - 3 * hour))))
    }

    @Test
    fun `unplugged below 70 percent nothing fetches, however old`() {
        assertEquals(emptyList<String>(), run(FakeHost(batteryLevel = 69, fetched = mutableMapOf("NWS" to now - 30 * hour))))
    }

    @Test
    fun `a source never fetched here counts as stale`() {
        assertEquals(listOf("NWS"), run(FakeHost(fetched = mutableMapOf("NWS" to null))))
    }

    @Test
    fun `a fetch that recorded nothing waits 15 min before asking again`() {
        val host = FakeHost(fetched = mutableMapOf("NWS" to now - 3 * hour))
        assertEquals(listOf("NWS"), run(host))
        assertEquals(emptyList<String>(), run(host, now + 15 * minute - 1))
        assertEquals(listOf("NWS"), run(host, now + 15 * minute))
    }
}
