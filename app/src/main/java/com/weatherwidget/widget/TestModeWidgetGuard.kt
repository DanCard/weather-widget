package com.weatherwidget.widget

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.WeatherDatabase
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps instrumented tests from painting the user's real widgets
 * (plans/261111-tests-never-paint-real-widgets.md).
 *
 * Instrumented tests run inside the app's process. Their database and preferences are separate
 * (`_test_default`), but widgets are system-wide: an untargeted `ACTION_REFRESH` from a test repainted the
 * emulator's launcher widget with test data (2026-11-11, `LocaleSwitchIntegrationTest`, widget 69).
 *
 * The test runner records the widget ids bound before any test runs ([protect]); while the app is in test
 * mode, every push seam asks [allows] first and skips those ids. Outside test mode nothing is blocked.
 */
object TestModeWidgetGuard {
    private const val TAG = "TestModeWidgetGuard"
    const val TAG_BLOCKED = "TEST_MODE_REAL_WIDGET_BLOCKED"

    @Volatile
    private var protectedIds: Set<Int> = emptySet()

    /** Ids skipped so far, in order; read by tests. */
    @VisibleForTesting
    val blockedForTesting: MutableList<Int> = CopyOnWriteArrayList()

    /** Called by the test runner with the provider's ids bound before any test runs. */
    fun protect(widgetIds: IntArray) {
        protectedIds = widgetIds.toSet()
        Log.i(TAG, "protecting real widgets during tests: ${protectedIds.sorted()}")
    }

    /** The ids currently protected; tests that add to them restore this afterwards. */
    @VisibleForTesting
    val protectedForTesting: Set<Int> get() = protectedIds

    @VisibleForTesting
    fun resetForTesting() {
        protectedIds = emptySet()
        blockedForTesting.clear()
    }

    /** The rule alone, for unit tests. */
    fun blocks(widgetId: Int, testMode: Boolean, protected: Set<Int>): Boolean = testMode && widgetId in protected

    /** False (and logged) when [widgetId] is a real widget and the app is running under tests. */
    fun allows(widgetId: Int, caller: String, origin: String = "-"): Boolean {
        if (!blocks(widgetId, WeatherDatabase.isTestingMode(), protectedIds)) return true
        blockedForTesting += widgetId
        Log.w(TAG_BLOCKED, "widget=$widgetId caller=$caller origin=$origin")
        return false
    }
}
