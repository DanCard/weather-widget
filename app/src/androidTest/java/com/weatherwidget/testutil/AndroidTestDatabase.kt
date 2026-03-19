package com.weatherwidget.testutil

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.data.local.WeatherDatabase

/**
 * Ensures instrumented tests use the shared instrumentation database that Hilt
 * wires at process start, then clears it between tests.
 *
 * Per-test database-name overrides are unsafe for connected test suites because
 * the app process and its Hilt singletons are reused across test classes.
 */
object AndroidTestDatabase {
    fun useIsolatedDatabase(nameSuffix: String): WeatherDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return WeatherDatabase.getDatabase(context).also { database ->
            database.clearAllTables()
        }
    }

    fun cleanup(
        nameSuffix: String,
        context: Context = InstrumentationRegistry.getInstrumentation().targetContext,
    ) {
        WeatherDatabase.getDatabase(context).clearAllTables()
    }
}
