package com.weatherwidget

import android.app.Application
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.CrashReporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration test for the crash-visibility pattern (notes/crash-visibility-pattern.md, Step 3):
 * the *installed* uncaught-exception handler must persist a readable CRASH row before delegating to
 * the previously-installed handler. CrashReporterTest already covers the pure formatter; this covers
 * the handler -> store wiring that CrashReporterTest cannot.
 *
 * Uses Robolectric + an in-memory Room DB (TestDatabase) rather than the real @HiltAndroidApp
 * Application — matching this module's convention of substituting a plain Application under
 * `@Config(application = ...)` to avoid standing up Hilt. The handler is obtained from the same
 * factory production uses (WeatherWidgetApp.buildCrashHandler), so the format/persist/chain contract
 * is exercised verbatim.
 */
@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WeatherWidgetAppCrashHandlerTest {
    private lateinit var db: WeatherDatabase
    private lateinit var dao: AppLogDao

    @Before
    fun setup() {
        db = TestDatabase.create()
        dao = db.appLogDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun installedHandler_persistsReadableCrashRow_thenChainsToPrevious() {
        var delegatedThrowable: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, t -> delegatedThrowable = t }
        val handler = WeatherWidgetApp.buildCrashHandler({ dao }, previous)

        val crash = RuntimeException("boom-xyz")
        try {
            handler.uncaughtException(Thread.currentThread(), crash)
        } catch (_: Throwable) {
            // The real delegate may rethrow/terminate; persistence runs before it, so the assertions
            // below stay deterministic regardless of the delegate's behavior.
        }

        val rows = runBlocking { dao.getLogsByTag(CrashReporter.CRASH_TAG, 10) }
        assertTrue("expected a CRASH-tagged row to be persisted", rows.isNotEmpty())
        val row = rows.first()
        assertEquals("crash rows are logged at ERROR level", "ERROR", row.level)
        assertTrue(
            "persisted crash should contain the throwable's trace",
            row.message.contains("boom-xyz"),
        )
        assertTrue(
            "persisted crash should record the crashing thread",
            row.message.contains("thread="),
        )
        assertEquals(
            "handler must chain to the previously-installed handler",
            crash,
            delegatedThrowable,
        )
    }
}
