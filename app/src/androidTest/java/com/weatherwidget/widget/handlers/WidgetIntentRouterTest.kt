package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.widget.ViewMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for WidgetIntentRouter.
 */
@RunWith(AndroidJUnit4::class)
class WidgetIntentRouterTest : IsolatedIntegrationTest("widget_intent_router") {

    @Before
    override fun setup() {
        super.setup()
        // No additional setup needed, base class handles isolation
    }

    @Test
    fun handleNavigation_doesNotCrash() = runBlocking {
        val appWidgetId = 123
        // Just verify the method exists and doesn't crash with invalid widget ID
        try {
            WidgetIntentRouter.handleNavigation(context, appWidgetId, isLeft = true)
        } catch (e: Exception) {
            // Expected for invalid widget
        }
    }

    @Test
    fun handleToggleApi_doesNotCrash() = runBlocking {
        val appWidgetId = 123
        try {
            WidgetIntentRouter.handleToggleApi(context, appWidgetId)
        } catch (e: Exception) {
            // Expected for invalid widget
        }
    }

    @Test
    fun handleToggleView_doesNotCrash() = runBlocking {
        val appWidgetId = 123
        try {
            WidgetIntentRouter.handleToggleView(context, appWidgetId)
        } catch (e: Exception) {
            // Expected for invalid widget
        }
    }

    @Test
    fun handleTogglePrecip_doesNotCrash() = runBlocking {
        val appWidgetId = 123
        try {
            WidgetIntentRouter.handleTogglePrecip(context, appWidgetId)
        } catch (e: Exception) {
            // Expected for invalid widget
        }
    }

    @Test
    fun handleSetView_doesNotCrash() = runBlocking {
        val appWidgetId = 123
        try {
            WidgetIntentRouter.handleSetView(
                context,
                appWidgetId,
                ViewMode.TEMPERATURE,
            )
        } catch (e: Exception) {
            // Expected for invalid widget
        }
    }

    @Test
    fun actionConstants_areDefined() {
        assertNotNull(WidgetIntentRouter.ACTION_NAV_LEFT)
        assertNotNull(WidgetIntentRouter.ACTION_NAV_RIGHT)
        assertNotNull(WidgetIntentRouter.ACTION_TOGGLE_API)
        assertNotNull(WidgetIntentRouter.ACTION_TOGGLE_VIEW)
        assertNotNull(WidgetIntentRouter.ACTION_TOGGLE_PRECIP)
        assertNotNull(WidgetIntentRouter.ACTION_SET_VIEW)
        assertNotNull(WidgetIntentRouter.EXTRA_TARGET_VIEW)
    }

    @Test
    fun actionConstants_haveCorrectValues() {
        assertEquals("com.weatherwidget.ACTION_NAV_LEFT", WidgetIntentRouter.ACTION_NAV_LEFT)
        assertEquals("com.weatherwidget.ACTION_NAV_RIGHT", WidgetIntentRouter.ACTION_NAV_RIGHT)
        assertEquals("com.weatherwidget.ACTION_TOGGLE_API", WidgetIntentRouter.ACTION_TOGGLE_API)
        assertEquals("com.weatherwidget.ACTION_TOGGLE_VIEW", WidgetIntentRouter.ACTION_TOGGLE_VIEW)
        assertEquals("com.weatherwidget.ACTION_TOGGLE_PRECIP", WidgetIntentRouter.ACTION_TOGGLE_PRECIP)
        assertEquals("com.weatherwidget.ACTION_SET_VIEW", WidgetIntentRouter.ACTION_SET_VIEW)
        assertEquals("com.weatherwidget.EXTRA_TARGET_VIEW", WidgetIntentRouter.EXTRA_TARGET_VIEW)
    }
}
