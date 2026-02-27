package com.weatherwidget.testutil

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.data.local.WeatherDatabase

/**
 * Ensures instrumented tests use an isolated on-device Room database instead of
 * the app's shared production database file.
 */
object AndroidTestDatabase {
    fun useIsolatedDatabase(nameSuffix: String): WeatherDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "weather_database_android_test_$nameSuffix"
        context.deleteDatabase(dbName)
        WeatherDatabase.setDatabaseNameOverrideForTesting(dbName)
        return WeatherDatabase.getDatabase(context)
    }

    fun cleanup(nameSuffix: String, context: Context = InstrumentationRegistry.getInstrumentation().targetContext) {
        val dbName = "weather_database_android_test_$nameSuffix"
        WeatherDatabase.setDatabaseNameOverrideForTesting(null)
        context.deleteDatabase(dbName)
    }
}
