package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WidgetActionJobRegistryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appWidgetId = 7711

    @After
    fun tearDown() {
        WidgetActionJobRegistry.clearForTesting()
        WidgetStateManager(context).clearWidgetState(appWidgetId)
    }

    @Test
    fun `deleting widget cancels active action before it can recreate state`() = runTest {
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(appWidgetId)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val action =
            launch {
                started.complete(Unit)
                release.await()
                stateManager.setDateOffset(appWidgetId, 7)
            }
        WidgetActionJobRegistry.track(appWidgetId, action)
        runCurrent()
        started.await()

        WeatherWidgetProvider().onDeleted(context, intArrayOf(appWidgetId))
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, stateManager.getDateOffset(appWidgetId))
    }
}
