package com.weatherwidget.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.testutil.IsolatedIntegrationTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An untargeted refresh under tests must not paint a widget that was on the launcher before the run
 * (plans/261111-tests-never-paint-real-widgets.md). `LocaleSwitchIntegrationTest` repainted the emulator's
 * real widget 69 with test data on 2026-11-11. Two bound test widgets stand in: [realId] is registered as
 * pre-existing, [testId] is the test's own.
 */
@RunWith(AndroidJUnit4::class)
class TestModeWidgetGuardIntegrationTest : IsolatedIntegrationTest("test_mode_widget_guard") {
    private lateinit var host: AppWidgetHost
    private var realId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var testId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var originalProtected: Set<Int> = emptySet()

    @Before
    override fun setup() {
        super.setup()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user 0"),
        ).use { it.readBytes() }
        host = AppWidgetHost(context, TEST_HOST_ID)
        originalProtected = TestModeWidgetGuard.protectedForTesting
    }

    @After
    override fun cleanup() {
        TestModeWidgetGuard.protect(originalProtected.toIntArray())
        TestModeWidgetGuard.blockedForTesting.clear()
        listOf(realId, testId).filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }.forEach { host.deleteAppWidgetId(it) }
        super.cleanup()
    }

    @Test
    fun untargetedRefreshSkipsThePreExistingWidgetAndPaintsTheTestOne() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, WeatherWidgetProvider::class.java)
        realId = host.allocateAppWidgetId()
        testId = host.allocateAppWidgetId()
        assertTrue(manager.bindAppWidgetIdIfAllowed(realId, provider, options()))
        assertTrue(manager.bindAppWidgetIdIfAllowed(testId, provider, options()))
        delay(SETTLE_MS) // let the bind-time onUpdate paints finish before the guard is armed

        // realId plays the user's launcher widget; the runner's own record stays protected too.
        TestModeWidgetGuard.protect((originalProtected + realId).toIntArray())
        TestModeWidgetGuard.blockedForTesting.clear()
        db.appLogDao().clearAllLogs()

        context.sendBroadcast(
            Intent(context, WidgetActionReceiver::class.java).apply {
                action = WidgetActions.ACTION_REFRESH
                putExtra(WidgetActions.EXTRA_UI_ONLY, true) // no EXTRA_APPWIDGET_ID: every widget
            },
        )

        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (realId !in TestModeWidgetGuard.blockedForTesting && System.currentTimeMillis() < deadline) delay(POLL_MS)
        val blocked = TestModeWidgetGuard.blockedForTesting.toList()
        println("TEST_MODE_GUARD blocked=$blocked realId=$realId testId=$testId protected=${TestModeWidgetGuard.protectedForTesting}")
        assertTrue("the pre-existing widget $realId must be blocked, blocked=$blocked", realId in blocked)
        assertFalse("the test's own widget $testId must not be blocked, blocked=$blocked", testId in blocked)
    }

    private fun options() = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 340)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 220)
    }

    companion object {
        private const val TEST_HOST_ID = 0x7E59
        private const val SETTLE_MS = 3_000L
        private const val TIMEOUT_MS = 20_000L
        private const val POLL_MS = 250L
    }
}
