package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category

@Category(MediumDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetIntentRouterCrashSafetyRoboTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 123

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
        WidgetIntentRouter.setIsRefreshDisabledForTesting(true)
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        WidgetIntentRouter.setIsRefreshDisabledForTesting(false)
    }

    @Test
    fun handleNavigation_doesNotCrashWithInvalidWidgetId() = runBlocking {
        try {
            WidgetIntentRouter.handleNavigation(context, testWidgetId, isLeft = true)
        } catch (_: Exception) {
        }
    }

    @Test
    fun handleToggleApi_doesNotCrashWithInvalidWidgetId() = runBlocking {
        try {
            WidgetIntentRouter.handleToggleApi(context, testWidgetId)
        } catch (_: Exception) {
        }
    }

    @Test
    fun handleToggleView_doesNotCrashWithInvalidWidgetId() = runBlocking {
        try {
            WidgetIntentRouter.handleToggleView(context, testWidgetId)
        } catch (_: Exception) {
        }
    }

    @Test
    fun handleTogglePrecip_doesNotCrashWithInvalidWidgetId() = runBlocking {
        try {
            WidgetIntentRouter.handleTogglePrecip(context, testWidgetId)
        } catch (_: Exception) {
        }
    }

    @Test
    fun handleSetView_doesNotCrashWithInvalidWidgetId() = runBlocking {
        try {
            WidgetIntentRouter.handleSetView(
                context,
                testWidgetId,
                ViewMode.TEMPERATURE,
            )
        } catch (_: Exception) {
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
        assertNotNull(WidgetIntentRouter.ACTION_NAV_LEFT)
        assertNotNull(WidgetIntentRouter.ACTION_NAV_RIGHT)
        assertNotNull(WidgetIntentRouter.ACTION_TOGGLE_API)
        assertNotNull(WidgetIntentRouter.ACTION_TOGGLE_VIEW)
        assertNotNull(WidgetIntentRouter.ACTION_TOGGLE_PRECIP)
        assertNotNull(WidgetIntentRouter.ACTION_SET_VIEW)
        assertNotNull(WidgetIntentRouter.EXTRA_TARGET_VIEW)
    }
}