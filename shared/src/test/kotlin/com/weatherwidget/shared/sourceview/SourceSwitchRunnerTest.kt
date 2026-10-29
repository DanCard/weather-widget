package com.weatherwidget.shared.sourceview

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.WeatherSource.GOOGLE_WEATHER
import com.weatherwidget.data.model.WeatherSource.SILURIAN
import com.weatherwidget.shared.sourceview.SourceSwitchRunner.RefreshOutcome
import com.weatherwidget.shared.util.Log
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.Collections

/**
 * The shared source-switch rule: persist then paint is the whole critical path; the view tally,
 * refresh check and app_logs row follow and can neither delay nor break it.
 * plans/261029-shared-source-switch-runner.md
 */
@Category(ShortDuration::class)
class SourceSwitchRunnerTest {
    private val logs = Collections.synchronizedList(mutableListOf<Triple<Log.Priority, String, Throwable?>>())
    private var clockMs = 1_000L

    @Before
    fun installSink() {
        Log.install { priority, _, msg, tr -> logs.add(Triple(priority, msg, tr)) }
    }

    @After
    fun tearDown() = Log.resetToDefault()

    private open inner class FakeHost(private val to: WeatherSource? = GOOGLE_WEATHER) : SourceSwitchRunner.Host {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val persisted = Collections.synchronizedList(mutableListOf<String>())

        override suspend fun persistSelection(): WeatherSource? {
            events += "persist"
            clockMs += 5
            return to
        }

        override suspend fun showFromCache(source: WeatherSource) {
            events += "paint:${source.id}"
            clockMs += 40
        }

        override suspend fun refreshIfStale(source: WeatherSource): RefreshOutcome {
            events += "refresh"
            return RefreshOutcome.QUEUED
        }

        override suspend fun recordView(source: WeatherSource) {
            events += "record"
        }

        override suspend fun persistLog(tag: String, message: String, level: String) {
            events += "log"
            persisted += "$tag $message"
        }
    }

    private fun runner(host: SourceSwitchRunner.Host) = SourceSwitchRunner(host, "test") { clockMs }

    private fun messages() = synchronized(logs) { logs.map { it.second } }

    @Test
    fun `critical path is persist then paint, then record, refresh and the persisted row`() = runBlocking {
        val host = FakeHost()
        runner(host).run(SILURIAN, SourceViewTrigger.TOGGLE, this)!!.join()

        assertEquals(listOf("persist", "paint:GOOGLE_WEATHER", "record", "refresh", "log"), host.events)
        val critical = messages().single { "phase=critical" in it }
        assertEquals(
            "SOURCE_SWITCH_E2E platform=test from=SILURIAN to=GOOGLE_WEATHER trigger=TOGGLE phase=critical " +
                "persistMs=5 paintMs=40 criticalMs=45",
            critical,
        )
        val row = host.persisted.single()
        assertTrue(row, row.startsWith("SOURCE_SWITCH_E2E platform=test from=SILURIAN to=GOOGLE_WEATHER"))
        assertTrue(row, "phase=done persistMs=5 paintMs=40 criticalMs=45 refresh=QUEUED deferredMs=0" in row)
    }

    @Test
    fun `a deferred phase stuck on a lock does not delay the switch`() = runBlocking {
        val neverReleased = CompletableDeferred<Unit>()
        val host = object : FakeHost() {
            override suspend fun recordView(source: WeatherSource) {
                super.recordView(source)
                neverReleased.await()
            }

            override suspend fun persistLog(tag: String, message: String, level: String) {
                super.persistLog(tag, message, level)
                neverReleased.await()
            }
        }
        val deferredScope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val job = withTimeout(2_000) { runner(host).run(SILURIAN, SourceViewTrigger.TOGGLE, deferredScope) }
            assertTrue(host.events.containsAll(listOf("persist", "paint:GOOGLE_WEATHER")))
            assertTrue(messages().any { "phase=critical" in it })
            assertTrue("deferred phase is still waiting", job!!.isActive)
        } finally {
            deferredScope.cancel()
        }
    }

    @Test
    fun `a failed deferred step is logged and the later steps still run`() = runBlocking {
        val boom = IllegalStateException("enqueue failed")
        val host = object : FakeHost() {
            override suspend fun recordView(source: WeatherSource) = throw IllegalStateException("db locked")
            override suspend fun refreshIfStale(source: WeatherSource): RefreshOutcome = throw boom
        }
        runner(host).run(SILURIAN, SourceViewTrigger.HOME, this)!!.join()

        assertEquals(listOf("persist", "paint:GOOGLE_WEATHER", "log"), host.events)
        assertTrue(host.persisted.single(), "refresh=FAILED" in host.persisted.single())
        val warnings = synchronized(logs) { logs.filter { it.first == Log.Priority.WARN } }
        assertEquals(2, warnings.size)
        assertTrue(warnings[0].second.endsWith("phase=deferred_failed step=record_view"))
        assertTrue(warnings[1].second.endsWith("phase=deferred_failed step=refresh"))
        assertSame(boom, warnings[1].third)
    }

    @Test
    fun `a failed persist is rethrown and nothing else runs`() = runBlocking {
        val boom = IllegalStateException("disk full")
        val host = object : FakeHost() {
            override suspend fun persistSelection(): WeatherSource? = throw boom
        }
        try {
            runner(host).run(SILURIAN, SourceViewTrigger.TOGGLE, this)
            fail("persist failure must propagate")
        } catch (e: IllegalStateException) {
            assertSame(boom, e)
        }
        assertTrue(host.events.isEmpty())
        assertTrue(messages().single().endsWith("phase=persist_failed"))
    }

    @Test
    fun `a failed paint still counts and logs the persisted switch, then is rethrown`() = runBlocking {
        val boom = IllegalStateException("RemoteViews too large")
        val deferredScope = CoroutineScope(Dispatchers.Default + Job())
        val host = object : FakeHost() {
            override suspend fun showFromCache(source: WeatherSource) = throw boom
        }
        try {
            runner(host).run(SILURIAN, SourceViewTrigger.TOGGLE, deferredScope)
            fail("paint failure must propagate")
        } catch (e: IllegalStateException) {
            assertSame(boom, e)
        }
        deferredScope.coroutineContext[Job]!!.children.forEach { it.join() }
        assertEquals(listOf("persist", "record", "refresh", "log"), host.events)
        assertTrue(host.persisted.single(), host.persisted.single().endsWith("paint=failed"))
        assertTrue(messages().none { "phase=critical" in it })
    }

    @Test
    fun `nothing to switch to - no paint, no deferred phase`() = runBlocking {
        val host = FakeHost(to = null)
        assertNull(runner(host).run(SILURIAN, SourceViewTrigger.TOGGLE, this))
        assertEquals(listOf("persist"), host.events)
    }

    @Test
    fun `cancelling the deferred phase is not a failure and still counts the view`() = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val host = object : FakeHost() {
            override suspend fun refreshIfStale(source: WeatherSource): RefreshOutcome {
                super.refreshIfStale(source)
                refreshStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val deferredScope = CoroutineScope(Dispatchers.Default + Job())
        val job = runner(host).run(SILURIAN, SourceViewTrigger.TOGGLE, deferredScope)!!
        refreshStarted.await()
        job.cancel()
        job.join()

        assertEquals(listOf("persist", "paint:GOOGLE_WEATHER", "record", "refresh"), host.events)
        assertTrue(messages().none { "deferred_failed" in it })
    }

    @Test
    fun `an interaction cancelled before the deferred phase starts still counts the view`() = runBlocking {
        val host = FakeHost()
        val cancelledScope = CoroutineScope(Dispatchers.Default + Job()).apply { cancel() }
        val job = runner(host).run(SILURIAN, SourceViewTrigger.TOGGLE, cancelledScope)!!
        job.join()

        assertEquals(listOf("persist", "paint:GOOGLE_WEATHER", "record"), host.events)
    }
}
