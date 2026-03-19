package com.weatherwidget.testutil

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.widget.WidgetStateManager

/**
 * Ensures instrumented tests use the shared instrumentation SharedPreferences
 * file that the test runner selects for the whole app process, then clears it
 * between tests.
 */
object AndroidTestWidgetState {
    fun useIsolatedPrefs(
        nameSuffix: String,
        context: Context = InstrumentationRegistry.getInstrumentation().targetContext,
    ): String {
        val prefsName = WidgetStateManager.DEFAULT_TEST_PREFS_NAME
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        return prefsName
    }

    fun cleanup(
        nameSuffix: String,
        context: Context = InstrumentationRegistry.getInstrumentation().targetContext,
    ) {
        val prefsName = WidgetStateManager.DEFAULT_TEST_PREFS_NAME
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
