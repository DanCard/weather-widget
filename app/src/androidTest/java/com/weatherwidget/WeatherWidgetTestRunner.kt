package com.weatherwidget

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.weatherwidget.data.local.WeatherDatabase
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import com.weatherwidget.widget.TestModeWidgetGuard
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager

/**
 * Custom test runner that ensures the application enters a safe 'test mode'
 * before any instrumented tests execute.
 */
class WeatherWidgetTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: android.os.Bundle?) {
        WeatherDatabase.setIsTesting(true)
        WidgetStateManager.setPrefsNameOverrideForTesting(WidgetStateManager.DEFAULT_TEST_PREFS_NAME)
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application?) {
        WeatherDatabase.setIsTesting(true)
        WidgetStateManager.setPrefsNameOverrideForTesting(WidgetStateManager.DEFAULT_TEST_PREFS_NAME)
        // The widgets already on the launcher are the user's: no test may paint them
        // (plans/261111-tests-never-paint-real-widgets.md). Recorded before any test binds its own.
        app?.let {
            runCatching {
                val ids = AppWidgetManager.getInstance(it)
                    .getAppWidgetIds(ComponentName(it, WeatherWidgetProvider::class.java))
                TestModeWidgetGuard.protect(ids)
            }.onFailure { e -> android.util.Log.w("WeatherWidgetTestRunner", "could not record real widgets", e) }
        }
        super.callApplicationOnCreate(app)
    }

    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        // Also ensure test mode is active during application instantiation
        WeatherDatabase.setIsTesting(true)
        WidgetStateManager.setPrefsNameOverrideForTesting(WidgetStateManager.DEFAULT_TEST_PREFS_NAME)
        return super.newApplication(cl, className, context)
    }
}
