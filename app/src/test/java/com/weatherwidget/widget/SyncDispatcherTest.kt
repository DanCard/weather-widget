package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The low-priority sync pool: its own named threads, each lowering its priority once, and no more of
 * them than [SyncDispatcher.THREADS] however much work is thrown at it
 * (`performance/261115-boot-first-paint-frozen-behind-startup-syncs.md`, phase 2).
 */
@Category(ShortDuration::class)
class SyncDispatcherTest {
    /** Coroutine debug mode (on in tests) appends ` @coroutine#N` to the thread's name. */
    private fun threadName(): String = Thread.currentThread().name.substringBefore(" @")

    @Test
    fun `work runs on named sync threads that lowered their priority first`() = runBlocking {
        val lowered = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = SyncDispatcher.create(threads = 2, setPriority = { lowered += threadName() })

        val ranOn = withContext(dispatcher) { threadName() }

        println("SyncDispatcherTest: ranOn=$ranOn lowered=$lowered")
        assertTrue(ranOn, ranOn.startsWith(SyncDispatcher.THREAD_NAME_PREFIX))
        assertTrue("priority lowered on the thread that ran the work: $lowered", ranOn in lowered)
    }

    @Test
    fun `the pool never runs more than its thread count at once`() = runBlocking {
        val dispatcher = SyncDispatcher.create(threads = 2, setPriority = {})
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val threads = Collections.synchronizedSet(mutableSetOf<String>())

        withTimeout(10_000) {
            (1..12).map {
                async(dispatcher) {
                    val now = running.incrementAndGet()
                    peak.accumulateAndGet(now, ::maxOf)
                    threads += threadName()
                    Thread.sleep(20) // CPU-bound stand-in: holds the thread, as the blend does
                    running.decrementAndGet()
                }
            }.awaitAll()
        }

        println("SyncDispatcherTest: peak=${peak.get()} threads=$threads")
        assertEquals(2, peak.get())
        assertEquals(setOf("sync-pool-1", "sync-pool-2"), threads.toSet())
    }

    /** A failing priority call must not take the sync down with it. */
    @Test
    fun `a pool that cannot lower its priority still runs work`() = runBlocking {
        val dispatcher = SyncDispatcher.create(threads = 1, setPriority = { throw SecurityException("denied") })
        val done = CompletableDeferred<String>()

        withContext(dispatcher) { done.complete(threadName()) }

        assertEquals("sync-pool-1", done.await())
    }

    @Test
    fun `the pool is two threads`() {
        assertEquals(2, SyncDispatcher.THREADS)
    }
}
