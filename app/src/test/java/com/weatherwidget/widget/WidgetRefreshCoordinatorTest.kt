package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(MediumDuration::class)
class WidgetRefreshCoordinatorTest {
    private val context = mockk<Context>()
    private val repository = mockk<WeatherRepository>()

    @Before
    fun setUp() {
        mockkObject(WidgetIntentRouter)
        coEvery { WidgetIntentRouter.renderAllWidgetsFromCache(any(), any()) } returns Unit
        coEvery { WidgetIntentRouter.renderWidgetFromCache(any(), any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkObject(WidgetIntentRouter)
    }

    @Test
    fun `targeted refresh repaints only the requested widget`() = runTest {
        WidgetRefreshCoordinator.repaintFromCache(context, repository, requestedWidgetId = 345)

        coVerify(exactly = 1) {
            WidgetIntentRouter.renderWidgetFromCache(context, 345, repository)
        }
        coVerify(exactly = 0) {
            WidgetIntentRouter.renderAllWidgetsFromCache(any(), any())
        }
    }

    @Test
    fun `global refresh still repaints every widget`() = runTest {
        WidgetRefreshCoordinator.repaintFromCache(context, repository, requestedWidgetId = null)

        coVerify(exactly = 1) {
            WidgetIntentRouter.renderAllWidgetsFromCache(context, repository)
        }
        coVerify(exactly = 0) {
            WidgetIntentRouter.renderWidgetFromCache(any(), any(), any())
        }
    }
}
