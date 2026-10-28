package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class WidgetInteractionReceiverTest {

    private lateinit var context: Context
    private lateinit var repository: WeatherRepository
    private lateinit var interactionReceiver: WidgetInteractionReceiver
    private lateinit var actionReceiver: WidgetActionReceiver
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val widgetId = 345

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = mockk(relaxed = true)
        mockkObject(WidgetIntentRouter)
        mockkObject(WidgetRefreshCoordinator)
        coEvery { WidgetRefreshCoordinator.restartHeartbeats(any()) } returns Unit

        interactionReceiver = WidgetInteractionReceiver().apply {
            this.repository = this@WidgetInteractionReceiverTest.repository
            this.scope = testScope
        }

        actionReceiver = WidgetActionReceiver().apply {
            this.repository = this@WidgetInteractionReceiverTest.repository
            this.scope = testScope
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `WidgetInteractionReceiver dispatches ACTION_TOGGLE_API`() = testScope.runTest {
        coEvery { WidgetIntentRouter.handleToggleApi(any(), any(), any()) } returns Unit

        val intent = Intent(context, WidgetInteractionReceiver::class.java).apply {
            action = WidgetActions.ACTION_TOGGLE_API
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }

        interactionReceiver.onReceive(context, intent)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            WidgetIntentRouter.handleToggleApi(any(), widgetId, any())
        }
    }

    @Test
    fun `WidgetInteractionReceiver dispatches ACTION_NAV_LEFT`() = testScope.runTest {
        coEvery { WidgetIntentRouter.handleNavigation(any(), any(), any(), any()) } returns Unit

        val intent = Intent(context, WidgetInteractionReceiver::class.java).apply {
            action = WidgetActions.ACTION_NAV_LEFT
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }

        interactionReceiver.onReceive(context, intent)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            WidgetIntentRouter.handleNavigation(any(), widgetId, true, any())
        }
    }

    @Test
    fun `WidgetActionReceiver retains legacy fallback for ACTION_TOGGLE_API`() = testScope.runTest {
        coEvery { WidgetIntentRouter.handleToggleApi(any(), any(), any()) } returns Unit

        val intent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActions.ACTION_TOGGLE_API
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }

        actionReceiver.onReceive(context, intent)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            WidgetIntentRouter.handleToggleApi(any(), widgetId, any())
        }
    }

    @Test
    fun `WidgetActionReceiver dispatches ACTION_REFRESH`() = testScope.runTest {
        coEvery {
            WidgetRefreshCoordinator.refresh(
                context = any(),
                uiOnly = any(),
                repository = any(),
                requestedWidgetId = any(),
                userInitiated = any(),
            )
        } returns Unit

        val intent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActions.ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra(WidgetActions.EXTRA_UI_ONLY, true)
        }

        actionReceiver.onReceive(context, intent)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            WidgetRefreshCoordinator.refresh(
                context = any(),
                uiOnly = true,
                repository = any(),
                requestedWidgetId = widgetId,
                userInitiated = false,
            )
        }
    }
}
