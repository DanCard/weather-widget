package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class WidgetInteractionCacheTest {

    @After
    fun tearDown() = WidgetInteractionCache.clear()

    private fun data() = WidgetInteractionCache.Data(weatherListRaw = emptyList(), dailyActuals = emptyMap())

    private fun key() = WidgetInteractionCache.Key.of(lat = 37.4219, lon = -122.0840, epochDay = 20_289L)

    @Test
    fun `hit within TTL returns the same cached instance`() {
        val d = data()
        WidgetInteractionCache.put(key(), d, nowElapsedMs = 1_000L)
        val got = WidgetInteractionCache.get(key(), nowElapsedMs = 1_000L + WidgetInteractionCache.TTL_MS)
        assertSame("entry within TTL must be reused", d, got)
    }

    @Test
    fun `entry older than TTL is a miss`() {
        WidgetInteractionCache.put(key(), data(), nowElapsedMs = 1_000L)
        val got = WidgetInteractionCache.get(key(), nowElapsedMs = 1_000L + WidgetInteractionCache.TTL_MS + 1)
        assertNull("stale entry must not be served", got)
    }

    @Test
    fun `different location is a miss even within TTL`() {
        WidgetInteractionCache.put(key(), data(), nowElapsedMs = 1_000L)
        val elsewhere = WidgetInteractionCache.Key.of(lat = 40.7128, lon = -74.0060, epochDay = 20_289L)
        assertNull(WidgetInteractionCache.get(elsewhere, nowElapsedMs = 1_100L))
    }

    @Test
    fun `different day is a miss even at same coordinates`() {
        WidgetInteractionCache.put(key(), data(), nowElapsedMs = 1_000L)
        val tomorrow = WidgetInteractionCache.Key.of(lat = 37.4219, lon = -122.0840, epochDay = 20_290L)
        assertNull(WidgetInteractionCache.get(tomorrow, nowElapsedMs = 1_100L))
    }

    @Test
    fun `coordinates within the same 3dp quantum share a key`() {
        // Sub-milli-degree GPS jitter must not fragment the cache.
        WidgetInteractionCache.put(
            WidgetInteractionCache.Key.of(lat = 37.42190, lon = -122.08400, epochDay = 20_289L),
            data(),
            nowElapsedMs = 1_000L,
        )
        val jittered = WidgetInteractionCache.Key.of(lat = 37.421904, lon = -122.084012, epochDay = 20_289L)
        assertNotNull("jitter under 3dp must hit the same entry", WidgetInteractionCache.get(jittered, 1_100L))
    }
}
