package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the goAsync() watchdog added to guard against the broadcast-ANR/process-kill scenario:
 * a widget interaction whose render is slow (e.g. competing with a startup fetch storm) used to
 * blow the ~10s foreground-broadcast deadline and get the process killed. The watchdog now
 * finishes the PendingResult early (best-effort) at [WeatherWidgetProvider.GO_ASYNC_WATCHDOG_MS]
 * while [block] keeps running in [WeatherWidgetProvider.scope] and completes whenever it can.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WeatherWidgetProviderLaunchAsyncWatchdogTest {

    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private lateinit var provider: WeatherWidgetProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(db)
        provider = WeatherWidgetProvider()
    }

    @After
    fun tearDown() {
        db.close()
        WeatherDatabase.resetInstanceForTesting()
    }

    @Test
    fun `watchdog fires and releases broadcast while a slow block keeps running to completion`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        provider.scope = CoroutineScope(SupervisorJob() + testDispatcher)

        var blockCompleted = false
        provider.launchAsync(context) {
            // Deliberately slower than the watchdog deadline.
            delay(WeatherWidgetProvider.GO_ASYNC_WATCHDOG_MS + 5_000L)
            blockCompleted = true
        }

        // Just past the watchdog deadline, before the slow block finishes.
        advanceTimeBy(WeatherWidgetProvider.GO_ASYNC_WATCHDOG_MS + 100L)
        assertFalse("block should still be running", blockCompleted)
        assertTrue(
            "watchdog should have logged CLICK_WATCHDOG once it fired",
            db.appLogDao().getLogsByTag("CLICK_WATCHDOG", 10).isNotEmpty(),
        )

        // Let the slow block run to completion — proves the watchdog's early finish() didn't
        // cancel it, only released the broadcast's ANR deadline early.
        advanceUntilIdle()
        assertTrue("block should have completed after the watchdog released the broadcast", blockCompleted)
    }

    @Test
    fun `watchdog does not fire when the block completes well within the deadline`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        provider.scope = CoroutineScope(SupervisorJob() + testDispatcher)

        var blockCompleted = false
        provider.launchAsync(context) {
            delay(500L)
            blockCompleted = true
        }

        advanceUntilIdle()

        assertTrue("block should have completed", blockCompleted)
        assertTrue(
            "watchdog should never have fired for a fast block",
            db.appLogDao().getLogsByTag("CLICK_WATCHDOG", 10).isEmpty(),
        )
    }

    @Test
    fun `watchdog firing and normal completion do not double-finish`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        provider.scope = CoroutineScope(SupervisorJob() + testDispatcher)

        // Both the watchdog and the block will attempt to finish the (null, in this harness)
        // PendingResult; finishOnce()'s guard must make that safe regardless of which fires first.
        provider.launchAsync(context) {
            delay(WeatherWidgetProvider.GO_ASYNC_WATCHDOG_MS + 1_000L)
        }

        // Should not throw (e.g. from a double PendingResult.finish()) as time crosses both the
        // watchdog deadline and the block's own completion.
        advanceUntilIdle()
    }
}
