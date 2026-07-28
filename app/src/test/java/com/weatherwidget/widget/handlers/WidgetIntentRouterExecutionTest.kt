package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@OptIn(ExperimentalCoroutinesApi::class)
@Category(ShortDuration::class)
class WidgetIntentRouterExecutionTest {

    @After
    fun tearDown() {
        WidgetIntentRouter.clearInteractionMutexesForTesting()
    }

    @Test
    fun `same widget interactions serialize while different widgets remain independent`() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        launch {
            WidgetIntentRouter.withWidgetInteractionLock(appWidgetId = 7) {
                events += "first-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                events += "first-end"
            }
        }
        firstEntered.await()

        launch {
            WidgetIntentRouter.withWidgetInteractionLock(appWidgetId = 7) {
                events += "second"
            }
        }
        launch {
            WidgetIntentRouter.withWidgetInteractionLock(appWidgetId = 8) {
                events += "other-widget"
            }
        }
        runCurrent()

        assertEquals(listOf("first-start", "other-widget"), events)

        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first-start", "other-widget", "first-end", "second"), events)
    }

    @Test
    fun `batch render continues after one widget fails`() = runTest {
        val rendered = mutableListOf<Int>()
        val failures = mutableListOf<Int>()

        WidgetIntentRouter.forEachWidgetIsolated(
            appWidgetIds = intArrayOf(1, 2, 3),
            onFailure = { widgetId, _ -> failures += widgetId },
        ) { widgetId ->
            if (widgetId == 2) error("render failed")
            rendered += widgetId
        }

        assertEquals(listOf(1, 3), rendered)
        assertEquals(listOf(2), failures)
    }

    @Test
    fun `batch render continues when failure reporting also fails`() = runTest {
        val rendered = mutableListOf<Int>()

        WidgetIntentRouter.forEachWidgetIsolated(
            appWidgetIds = intArrayOf(1, 2, 3),
            onFailure = { _, _ -> error("reporting failed") },
        ) { widgetId ->
            if (widgetId == 2) error("render failed")
            rendered += widgetId
        }

        assertEquals(listOf(1, 3), rendered)
    }

    @Test
    fun `resize debounce keeps only the newest request per widget`() = runTest {
        val survived = mutableListOf<Int>()

        // Three rapid resize events for widget 7, as a drag emits. Only the last may render.
        repeat(3) { index ->
            launch {
                if (WidgetIntentRouter.awaitLatestResizeRequest(appWidgetId = 7)) survived += index
            }
            runCurrent()
        }
        advanceUntilIdle()

        assertEquals(listOf(2), survived)
    }

    @Test
    fun `resize debounce treats widgets independently`() = runTest {
        val survived = mutableListOf<Int>()

        launch { if (WidgetIntentRouter.awaitLatestResizeRequest(appWidgetId = 7)) survived += 7 }
        launch { if (WidgetIntentRouter.awaitLatestResizeRequest(appWidgetId = 8)) survived += 8 }
        advanceUntilIdle()

        assertEquals(listOf(7, 8), survived)
    }

    @Test
    fun `resize debounce does not hold the interaction lock while waiting`() = runTest {
        val events = mutableListOf<String>()

        launch {
            if (WidgetIntentRouter.awaitLatestResizeRequest(appWidgetId = 7)) events += "resize-render"
        }
        runCurrent()

        // The debounce is sleeping. A tap on the same widget must still acquire the lock immediately —
        // this is the regression the additive-sleep-under-lock bug caused.
        launch {
            WidgetIntentRouter.withWidgetInteractionLock(appWidgetId = 7) { events += "tap" }
        }
        runCurrent()

        assertEquals(listOf("tap"), events)

        advanceUntilIdle()
        assertEquals(listOf("tap", "resize-render"), events)
    }

    @Test
    fun `batch render propagates cancellation and stops later widgets`() = runTest {
        val rendered = mutableListOf<Int>()
        var cancelled = false

        try {
            WidgetIntentRouter.forEachWidgetIsolated(intArrayOf(1, 2, 3)) { widgetId ->
                rendered += widgetId
                if (widgetId == 2) throw CancellationException("cancel")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(listOf(1, 2), rendered)
    }

    // ----------------------------------------------------------------------
    // runInteractionWithDao — the breadcrumb wrapper extracted from runInteraction so its
    // success/FAIL/cancellation semantics can be tested without a Room harness. Locks in the
    // N1 fix: a success-side log write failure cannot flip the outcome to `_FAIL`.
    // ----------------------------------------------------------------------

    @Test
    fun `runInteractionWithDao writes RENDER_OK and not FAIL when block succeeds`() = runTest {
        val appLogDao = mockk<AppLogDao>(relaxed = true)
        var blockRan = false

        WidgetIntentRouter.runInteractionWithDao(appLogDao, appWidgetId = 7, tag = "X") {
            blockRan = true
        }

        assertTrue("block must run on success path", blockRan)
        coVerify(exactly = 1) { appLogDao.insert(match { it.tag == "X_RENDER_OK" }) }
        coVerify(exactly = 0) { appLogDao.insert(match { it.tag == "X_FAIL" }) }
    }

    @Test
    fun `runInteractionWithDao appends metadata suffix to the RENDER_OK row`() = runTest {
        val appLogDao = mockk<AppLogDao>(relaxed = true)

        WidgetIntentRouter.runInteractionWithDao(
            appLogDao, appWidgetId = 9, tag = "TOGGLE_API", metadata = "from=NWS",
        ) { /* success */ }

        coVerify(exactly = 1) {
            appLogDao.insert(match { it.tag == "TOGGLE_API_RENDER_OK" && it.message.contains("widget=9") && it.message.contains("from=NWS") })
        }
    }

    @Test
    fun `runInteractionWithDao writes FAIL and swallows when block throws`() = runTest {
        val appLogDao = mockk<AppLogDao>(relaxed = true)

        // The wrapper catches non-cancellation exceptions and writes FAIL; the caller never sees
        // the throw (the whole point of the wrapper). Verified here by absence of an escape.
        WidgetIntentRouter.runInteractionWithDao(appLogDao, appWidgetId = 7, tag = "X") {
            throw RuntimeException("render boom")
        }

        coVerify(exactly = 0) { appLogDao.insert(match { it.tag == "X_RENDER_OK" }) }
        coVerify(exactly = 1) {
            appLogDao.insert(match { it.tag == "X_FAIL" && it.message.contains("render boom") })
        }
    }

    @Test
    fun `runInteractionWithDao propagates CancellationException without writing any breadcrumb`() = runTest {
        val appLogDao = mockk<AppLogDao>(relaxed = true)
        var propagated = false

        try {
            WidgetIntentRouter.runInteractionWithDao(appLogDao, appWidgetId = 7, tag = "X") {
                throw CancellationException("scope gone")
            }
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue("CancellationException must propagate, not be swallowed", propagated)
        coVerify(exactly = 0) { appLogDao.insert(any()) }
    }

    /**
     * N1 regression: if the `app_logs` write itself throws (a future regression in the
     * `AppLogDao.log` extension that removes its internal try/catch, or any other logging
     * pipeline change), the wrapper must NOT mislabel the successful render as `_FAIL`. The
     * original code emitted the OK breadcrumb INSIDE the try that wrapped `block()`, so a
     * throw from the OK log fell into the catch and wrote `_FAIL` even though the render had
     * succeeded. The fix moves the OK log into its own `runCatching` outside the try.
     *
     * Uses `mockkStatic` to make the extension itself throw — mocking `insert` alone would not
     * exercise the bug, because the extension's own try/catch swallows insert failures today.
     */
    @Test
    fun `runInteractionWithDao does not write FAIL when success-side log extension itself throws`() = runTest {
        val appLogDao = mockk<AppLogDao>(relaxed = true)
        mockkStatic("com.weatherwidget.data.local.AppLogEntityKt")
        try {
            // Force the extension to throw on the OK path. This simulates a future regression that
            // removes the extension's internal try/catch, or any other failure between the wrapper
            // and the persisted row.
            coEvery { appLogDao.log(any(), any(), any()) } throws RuntimeException("log pipeline unavailable")
            var blockRan = false

            WidgetIntentRouter.runInteractionWithDao(appLogDao, appWidgetId = 7, tag = "X") {
                blockRan = true
            }

            assertTrue("block must still have completed before the OK log was attempted", blockRan)
            // No FAIL insert should land: the failure was in the OK-side log, not the render itself.
            // (Before N1, the OK throw would fall into the wrapper's catch and write FAIL here.)
            coVerify(exactly = 0) { appLogDao.insert(match { it.tag == "X_FAIL" }) }
        } finally {
            unmockkStatic("com.weatherwidget.data.local.AppLogEntityKt")
        }
    }

    /**
     * Companion to the above: when the success-side log throws, the wrapper surfaces a warning to
     * logcat via `onFailure` but does not let the exception escape. The render's mutation already
     * happened; the caller should not see a crash from a logging failure.
     */
    @Test
    fun `runInteractionWithDao swallows success-side log failure without propagating`() = runTest {
        val appLogDao = mockk<AppLogDao>(relaxed = true)
        mockkStatic("com.weatherwidget.data.local.AppLogEntityKt")
        try {
            coEvery { appLogDao.log(any(), any(), any()) } throws RuntimeException("log pipeline unavailable")
            var escaped: Exception? = null

            try {
                WidgetIntentRouter.runInteractionWithDao(appLogDao, appWidgetId = 7, tag = "X") { /* ok */ }
            } catch (e: Exception) {
                escaped = e
            }

            assertFalse("success-side log failure must not propagate to the caller", escaped is Exception && escaped !is CancellationException)
        } finally {
            unmockkStatic("com.weatherwidget.data.local.AppLogEntityKt")
        }
    }
}
