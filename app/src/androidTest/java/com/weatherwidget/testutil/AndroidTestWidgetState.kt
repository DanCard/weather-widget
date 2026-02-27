package com.weatherwidget.testutil

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.widget.WidgetStateManager

/**
 * Ensures instrumented tests use isolated SharedPreferences for widget state.
 */
object AndroidTestWidgetState {
    fun useIsolatedPrefs(nameSuffix: String, context: Context = InstrumentationRegistry.getInstrumentation().targetContext): String {
        val prefsName = "widget_state_prefs_android_test_$nameSuffix"
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        WidgetStateManager.setPrefsNameOverrideForTesting(prefsName)
        return prefsName
    }

    fun cleanup(nameSuffix: String, context: Context = InstrumentationRegistry.getInstrumentation().targetContext) {
        val prefsName = "widget_state_prefs_android_test_$nameSuffix"
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
    }
}
