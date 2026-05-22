package com.weatherwidget.widget

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.LongDuration
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class CloudCoverGraphWatermarkTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `renderGraph uses mostly cloudy icon as background watermark`() {
        mockkStatic(ContextCompat::class)
        
        // Mock getDrawable to return a dummy/null but record the call
        every { ContextCompat.getDrawable(any(), any()) } returns null

        val start = LocalDateTime.of(2026, 4, 7, 10, 0)
        val hours = listOf(
            CloudCoverGraphRenderer.CloudHourData(
                dateTime = start,
                cloudCover = 10,
                label = "10a",
                isCurrentHour = true
            ),
            CloudCoverGraphRenderer.CloudHourData(
                dateTime = start.plusHours(1),
                cloudCover = 20,
                label = "11a"
            ),
            CloudCoverGraphRenderer.CloudHourData(
                dateTime = start.plusHours(2),
                cloudCover = 30,
                label = "12p"
            )
        )

        // Run renderer
        runBlocking {
            CloudCoverGraphRenderer.renderGraph(
                context = context,
                hours = hours,
                widthPx = 500,
                heightPx = 200,
                currentTime = start
            )
        }

        // Verify that ic_weather_mostly_cloudy was requested at least once
        verify {
            ContextCompat.getDrawable(any(), R.drawable.ic_weather_mostly_cloudy)
        }
        
        io.mockk.unmockkStatic(ContextCompat::class)
    }
}
