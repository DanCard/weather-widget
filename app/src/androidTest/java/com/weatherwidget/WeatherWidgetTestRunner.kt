package com.weatherwidget

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.weatherwidget.data.local.WeatherDatabase

/**
 * Custom test runner that ensures the application enters a safe 'test mode'
 * before any instrumented tests execute.
 */
class WeatherWidgetTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: android.os.Bundle?) {
        WeatherDatabase.setIsTesting(true)
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application?) {
        WeatherDatabase.setIsTesting(true)
        super.callApplicationOnCreate(app)
    }

    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        // Also ensure test mode is active during application instantiation
        WeatherDatabase.setIsTesting(true)
        return super.newApplication(cl, className, context)
    }
}
