package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The rule both platforms share: a screen closing mid-fetch cancels its waiting, never the fetch
 * (plans/261013-observations-refresh-survives-closing-the-screen.md; Fold 4 Observations Refresh
 * logged "Job was cancelled" the instant the screen finished).
 */
@Category(ShortDuration::class)
class UserRefreshRunnerTest {
    private val logs: MutableList<String> = CopyOnWriteArrayList()
    private fun runner(scope: CoroutineScope) = UserRefreshRunner(scope) { tag, msg, level -> logs += "$tag|$level|$msg" }

    @Test
    fun `closing the screen mid-fetch cancels only the waiting - the fetch lands and afterDetached runs`() = runBlocking {
        val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val release = CompletableDeferred<Unit>()
        val stored = CompletableDeferred<Int>()
        val followUp = CompletableDeferred<Int>()

        val refresh = runner(app).start("obs", afterDetached = { followUp.complete(it) }) {
            release.await()
            42.also { stored.complete(it) }
        }
        closeScreenWhileWaiting(refresh)
        release.complete(Unit)

        withTimeout(5_000) {
            assertEquals(42, stored.await())
            assertEquals(42, followUp.await())
        }
        waitForLog("outcome=completed")
        assertTrue(logs.toString(), logs.any { it == "USER_REFRESH_DETACHED|INFO|origin=obs outcome=waiting" })
        app.coroutineContext[Job]!!.cancel()
    }

    @Test
    fun `an attached await returns the result and never runs afterDetached`() = runBlocking {
        val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var followUps = 0
        val result = runner(app).start("obs", afterDetached = { followUps++ }) { 7 }.await()
        assertEquals(7, result)
        assertEquals(0, followUps)
        assertTrue(logs.isEmpty())
        app.coroutineContext[Job]!!.cancel()
    }

    @Test
    fun `a failing fetch reaches the waiter and does not cancel a plain-Job scope`() = runBlocking {
        // Desktop's uiScope is a rememberCoroutineScope: a plain Job, not a supervisor.
        val appJob = Job()
        val app = CoroutineScope(appJob + Dispatchers.IO)
        try {
            runner(app).start("desktop") { error("network down") }.await()
            fail("expected the fetch's exception")
        } catch (e: IllegalStateException) {
            assertEquals("network down", e.message)
        }
        assertTrue("the app scope must survive a failed refresh", appJob.isActive)
        // And it still runs the next one.
        assertEquals(1, runner(app).start("desktop") { 1 }.await())
        appJob.cancel()
    }

    @Test
    fun `a detached fetch that fails is logged`() = runBlocking {
        val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val release = CompletableDeferred<Unit>()
        val refresh = runner(app).start<Int>("history") {
            release.await()
            error("quota")
        }
        closeScreenWhileWaiting(refresh)
        release.complete(Unit)
        waitForLog("outcome=failed")
        assertTrue(logs.toString(), logs.any { it.startsWith("USER_REFRESH_DETACHED|WARN|origin=history outcome=failed IllegalStateException: quota") })
        app.coroutineContext[Job]!!.cancel()
    }

    /** The screen: waits on its own scope, then is destroyed while the fetch is still in flight. */
    private suspend fun <T> closeScreenWhileWaiting(refresh: UserRefreshRunner.UserRefresh<T>) {
        val screenScope = CoroutineScope(Job() + Dispatchers.IO)
        val waiting = CompletableDeferred<Unit>()
        val screen = screenScope.launch {
            waiting.complete(Unit)
            refresh.await()
        }
        waiting.await()
        kotlinx.coroutines.delay(50) // let it suspend inside await()
        screen.cancelAndJoin()
        waitForLog("outcome=waiting")
    }

    private suspend fun waitForLog(fragment: String) = withTimeout(5_000) {
        while (logs.none { it.contains(fragment) }) kotlinx.coroutines.delay(5)
    }
}
