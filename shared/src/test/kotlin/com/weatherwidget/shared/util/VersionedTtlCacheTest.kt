package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.concurrent.atomic.AtomicInteger

/**
 * The widget interaction cache's rule on both sides of a write: an entry is served for its TTL only
 * while no stored write has landed since its load (2026-10-29: a History refresh stored Sat 21% and the
 * repaint after it drew the cached 12%; plans/261029-stored-data-version-invalidates-interaction-cache.md).
 */
@Category(ShortDuration::class)
class VersionedTtlCacheTest {
    private val version = StoredDataVersion()
    private val cache = VersionedTtlCache<String, Any>(ttlMs = 60_000L, version = version)

    @Test
    fun `version bumps are monotonic`() {
        assertEquals(0L, version.current())
        assertEquals(1L, version.bump("a"))
        assertEquals(2L, version.bump("b"))
        assertEquals(2L, version.current())
    }

    @Test
    fun `served within the TTL while nothing was written`() {
        val v = Any()
        cache.put("k", v, nowMs = 1_000L)
        assertSame(v, cache.get("k", nowMs = 60_999L))
    }

    @Test
    fun `a stored write inside the TTL is a miss`() {
        cache.put("k", Any(), nowMs = 1_000L)
        version.bump("forecast_persist")
        assertNull(cache.get("k", nowMs = 1_001L))
    }

    @Test
    fun `expired after the TTL`() {
        cache.put("k", Any(), nowMs = 1_000L)
        assertNull(cache.get("k", nowMs = 61_001L))
    }

    @Test
    fun `a write that lands while the load runs leaves its entry stale`() = runBlocking {
        val loaded = Any()
        val got = cache.getOrLoad("k", { 1_000L }) {
            version.bump("mid_load_write")
            loaded
        }
        assertSame("the caller still gets what it read", loaded, got)
        assertNull("but the next read must not reuse a load older than the write", cache.get("k", 1_001L))
    }

    @Test
    fun `after a write the next getOrLoad reloads`() = runBlocking {
        val loads = AtomicInteger()
        cache.getOrLoad("k", { 1_000L }) { loads.incrementAndGet() }
        cache.getOrLoad("k", { 1_000L }) { loads.incrementAndGet() }
        assertEquals(1, loads.get())
        version.bump("write")
        assertEquals(2, cache.getOrLoad("k", { 1_000L }) { loads.incrementAndGet() })
    }

    @Test
    fun `concurrent misses for one key load once`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val first = async { cache.getOrLoad("k", { 1_000L }) { loads.incrementAndGet(); release.await(); "v" } }
        yield()
        val second = async { cache.getOrLoad("k", { 1_000L }) { loads.incrementAndGet(); "other" } }
        yield()
        release.complete(Unit)
        assertEquals("v", first.await())
        assertEquals("v", second.await())
        assertEquals(1, loads.get())
    }

    @Test
    fun `keys are independent`() {
        cache.put("a", 1, nowMs = 1_000L)
        assertNull(cache.get("b", nowMs = 1_000L))
        assertEquals(1, cache.get("a", nowMs = 1_000L))
    }
}
