package com.weatherwidget.testutil

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import org.junit.After
import org.junit.Before

/**
 * Base class for instrumented integration tests that require an isolated database
 * and isolated shared preferences to prevent corruption of production data.
 */
abstract class IsolatedIntegrationTest(private val nameSuffix: String) {
    protected lateinit var context: Context
    protected lateinit var db: WeatherDatabase

    @Before
    open fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        // 1. Force isolated preferences
        AndroidTestWidgetState.useIsolatedPrefs(nameSuffix, context)
        
        // 2. Force isolated database
        db = AndroidTestDatabase.useIsolatedDatabase(nameSuffix)
        
        // 3. Disable background refreshes during tests to avoid race conditions/network hits
        WidgetIntentRouter.setDisableRefreshForTesting(true)
    }

    @After
    open fun cleanup() {
        // 1. Clear shared test storage without closing process-wide singletons.
        AndroidTestDatabase.cleanup(nameSuffix, context)
        
        // 2. Clean up preferences
        AndroidTestWidgetState.cleanup(nameSuffix, context)
        
        // 3. Re-enable refreshes
        WidgetIntentRouter.setDisableRefreshForTesting(false)
    }
}
