package com.weatherwidget.widget

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.ShortDuration
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Category(ShortDuration::class)
class PrecipitationGraphWatermarkTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `renderGraph uses rainy icon as background watermark`() {
        mockkStatic(ContextCompat::class)
        
        // Mock getDrawable to return a dummy/null but record the call
        every { ContextCompat.getDrawable(any(), any()) } returns null

        val start = LocalDateTime.of(2026, 4, 7, 10, 0)
        val hours = listOf(
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start,
                precipProbability = 10,
                label = "10a"
            ),
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(1),
                precipProbability = 20,
                label = "11a"
            ),
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = start.plusHours(2),
                precipProbability = 30,
                label = "12p"
            )
        )

        // Run renderer
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 500,
            heightPx = 200,
            currentTime = start
        )

        // Verify that ic_weather_rain was requested at least once
        // (It's requested at the bottom of the function for the watermark)
        verify {
            ContextCompat.getDrawable(any(), R.drawable.ic_weather_rain)
        }
        
        io.mockk.unmockkStatic(ContextCompat::class)
    }
}
