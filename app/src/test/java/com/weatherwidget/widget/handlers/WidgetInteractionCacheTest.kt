package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `concurrent same-key misses execute loader once`() = runTest {
        val loaderEntered = CompletableDeferred<Unit>()
        val releaseLoader = CompletableDeferred<Unit>()
        val loadCount = AtomicInteger()
        val expected = data()
        val clock = { 1_000L }

        val first = async {
            WidgetInteractionCache.getOrLoad(key(), clock) {
                loadCount.incrementAndGet()
                loaderEntered.complete(Unit)
                releaseLoader.await()
                expected
            }
        }
        loaderEntered.await()
        val second = async {
            WidgetInteractionCache.getOrLoad(key(), clock) {
                loadCount.incrementAndGet()
                data()
            }
        }
        runCurrent()

        assertEquals(1, loadCount.get())
        releaseLoader.complete(Unit)
        assertSame(expected, first.await())
        assertSame(expected, second.await())
        assertEquals(1, loadCount.get())
    }

    @Test
    fun `different keys load independently`() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val elsewhere = WidgetInteractionCache.Key.of(40.7128, -74.0060, 20_289L)

        val first = async {
            WidgetInteractionCache.getOrLoad(key(), { 1_000L }) {
                firstEntered.complete(Unit)
                release.await()
                data()
            }
        }
        firstEntered.await()
        val second = async {
            WidgetInteractionCache.getOrLoad(elsewhere, { 1_000L }) {
                secondEntered.complete(Unit)
                release.await()
                data()
            }
        }

        secondEntered.await()
        release.complete(Unit)
        first.await()
        second.await()
    }

    @Test
    fun `TTL starts when a slow load completes`() = runTest {
        var nowMs = 1_000L
        val expected = data()

        WidgetInteractionCache.getOrLoad(key(), { nowMs }) {
            nowMs += WidgetInteractionCache.TTL_MS
            expected
        }

        assertSame(
            expected,
            WidgetInteractionCache.get(key(), nowElapsedMs = nowMs + WidgetInteractionCache.TTL_MS),
        )
    }
}
