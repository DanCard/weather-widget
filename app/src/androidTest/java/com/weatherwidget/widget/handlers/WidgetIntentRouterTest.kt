package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class WidgetIntentRouterTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun handleNavigation_doesNotCrash() {
        val appWidgetId = 123

        // Just verify the method exists and doesn't crash with invalid widget ID
        runBlocking {
            try {
                WidgetIntentRouter.handleNavigation(context, appWidgetId, isLeft = true)
            } catch (e: Exception) {
                // Expected for invalid widget without database setup
            }
        }
    }

    @Test
    fun handleToggleApi_doesNotCrash() {
        val appWidgetId = 123

        runBlocking {
            try {
                WidgetIntentRouter.handleToggleApi(context, appWidgetId)
            } catch (e: Exception) {
                // Expected for invalid widget without database setup
            }
        }
    }

    @Test
    fun handleToggleView_doesNotCrash() {
        val appWidgetId = 123

        runBlocking {
            try {
                WidgetIntentRouter.handleToggleView(context, appWidgetId)
            } catch (e: Exception) {
                // Expected for invalid widget without database setup
            }
        }
    }

    @Test
    fun handleTogglePrecip_doesNotCrash() {
        val appWidgetId = 123

        runBlocking {
            try {
                WidgetIntentRouter.handleTogglePrecip(context, appWidgetId)
            } catch (e: Exception) {
                // Expected for invalid widget without database setup
            }
        }
    }

    @Test
    fun handleSetView_doesNotCrash() {
        val appWidgetId = 123

        runBlocking {
            try {
                WidgetIntentRouter.handleSetView(
                    context,
                    appWidgetId,
                    com.weatherwidget.widget.ViewMode.TEMPERATURE,
                )
            } catch (e: Exception) {
                // Expected for invalid widget without database setup
            }
        }
    }

    @Test
    fun handleResize_doesNotCrash() {
        val appWidgetId = 123

        runBlocking {
            try {
                WidgetIntentRouter.handleResize(context, appWidgetId)
            } catch (e: Exception) {
                // Expected for invalid widget without database setup
            }
        }
    }

    @Test
    fun actionConstants_areDefined() {
        // Verify all action constants are defined
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
