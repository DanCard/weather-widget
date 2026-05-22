package com.weatherwidget.widget

import android.content.BroadcastReceiver
import com.weatherwidget.test.category.LongDuration
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(LongDuration::class)
class WeatherWidgetProviderPendingResultTest {

    @Test
    fun `finishPendingResultSafely ignores null pending result`() {
        WeatherWidgetProvider.finishPendingResultSafely(
            pendingResult = null,
            caller = "test",
        )
    }

    @Test
    fun `finishPendingResultSafely finishes non-null pending result`() {
        val pendingResult = mockk<BroadcastReceiver.PendingResult>(relaxed = true)

        WeatherWidgetProvider.finishPendingResultSafely(
            pendingResult = pendingResult,
            caller = "test",
        )

        verify { pendingResult.finish() }
    }

    @Test
    fun `finishPendingResultSafely swallows exception from finish`() {
        val pendingResult = mockk<BroadcastReceiver.PendingResult>()
        every { pendingResult.finish() } throws RuntimeException("boom")

        WeatherWidgetProvider.finishPendingResultSafely(
            pendingResult = pendingResult,
            caller = "test",
        )
    }
}
