package com.weatherwidget.testutil

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomStage
import com.weatherwidget.widget.ZoomWindow
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Deterministic helpers for waiting on WidgetStateManager state changes in instrumented tests.
 * Replaces flaky Thread.sleep() calls with event-driven SharedPreferences listeners.
 */
object WidgetStateTestUtils {

    fun waitForViewMode(context: Context, stateManager: WidgetStateManager, widgetId: Int, expected: ViewMode) {
        waitFor(context, "widget_view_mode_$widgetId") {
            stateManager.getViewMode(widgetId) == expected
        }
    }

    /** Waits on the persisted zoom *stage*; the resolved window is derived, not stored. */
    fun waitForZoomLevel(context: Context, stateManager: WidgetStateManager, widgetId: Int, expected: ZoomStage) {
        waitFor(context, "widget_zoom_level_$widgetId") {
            stateManager.getZoomStage(widgetId) == expected
        }
    }

    fun waitForDateOffset(context: Context, stateManager: WidgetStateManager, widgetId: Int, expected: Int) {
        waitFor(context, "widget_date_offset_$widgetId") {
            stateManager.getDateOffset(widgetId) == expected
        }
    }

    private fun waitFor(
        context: Context,
        key: String,
        timeoutSeconds: Long = 15,
        predicate: () -> Boolean
    ) {
        if (predicate()) return

        val prefs = SharedPreferencesUtil.getPrefs(context, WidgetStateManager.getPrefsNameForTesting())
        val latch = CountDownLatch(1)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            // changedKey is null when prefs are cleared.
            if (changedKey == null || changedKey == key) {
                if (predicate()) latch.countDown()
            }
        }

        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            // Guard against the write landing before listener registration.
            if (predicate()) latch.countDown()
            assertTrue(
                "Timed out waiting for preference change on key: $key",
                latch.await(timeoutSeconds, TimeUnit.SECONDS)
            )
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}
