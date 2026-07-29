package com.weatherwidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class BroadcastAsyncRunnerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `fast completion finishes once and cancels watchdog`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pendingResult = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
        var watchdogLogged = false

        BroadcastAsyncRunner.launch(
            context = context,
            pendingResult = pendingResult,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            caller = "test",
            watchdogLogger = { _, _ -> watchdogLogged = true },
        ) {
            delay(10)
        }
        advanceUntilIdle()

        verify(exactly = 1) { pendingResult.finish() }
        assertFalse(watchdogLogged)
    }

    @Test
    fun `watchdog finishes before blocked diagnostic logging`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pendingResult = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
        val loggerStarted = CompletableDeferred<Unit>()
        val releaseLogger = CompletableDeferred<Unit>()

        val job =
            BroadcastAsyncRunner.launch(
                context = context,
                pendingResult = pendingResult,
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                caller = "test",
                watchdogLogger = { _, _ ->
                    loggerStarted.complete(Unit)
                    releaseLogger.await()
                },
            ) {
                delay(BroadcastAsyncRunner.WATCHDOG_MS + 5_000)
            }

        advanceTimeBy(BroadcastAsyncRunner.WATCHDOG_MS)
        runCurrent()

        assertTrue(loggerStarted.isCompleted)
        verify(exactly = 1) { pendingResult.finish() }

        releaseLogger.complete(Unit)
        advanceUntilIdle()
        job.join()
        verify(exactly = 1) { pendingResult.finish() }
    }

    @Test
    fun `lazy action cancelled before start still finishes without running`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pendingResult = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
        var blockStarted = false

        val job =
            BroadcastAsyncRunner.launch(
                context = context,
                pendingResult = pendingResult,
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                caller = "test",
                start = CoroutineStart.LAZY,
            ) {
                blockStarted = true
            }

        job.cancel()
        job.start()
        advanceUntilIdle()

        assertFalse(blockStarted)
        verify(exactly = 1) { pendingResult.finish() }
    }
}
