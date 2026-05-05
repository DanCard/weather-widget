package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.MediumDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(MediumDuration::class)
class CloudCoverGraphLabelPlacementRobolectricTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `right edge low cloud label moves above when below placement would overlap icon`() {
        val start = LocalDateTime.of(2026, 5, 5, 9, 0)
        val hours = listOf(40, 24, 3).mapIndexed { index, cloudCover ->
            val dateTime = start.plusHours(index.toLong())
            CloudCoverGraphRenderer.CloudHourData(
                dateTime = dateTime,
                cloudCover = cloudCover,
                label = formatHour(dateTime.hour),
                iconRes = R.drawable.ic_weather_mostly_cloudy,
                showLabel = true,
                isCurrentHour = index == 0,
            )
        }
        val placements = mutableListOf<CloudCoverGraphRenderer.LabelPlacementDebug>()

        CloudCoverGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 400,
            currentTime = start.plusHours(1),
            onLabelPlaced = { placements.add(it) },
        )

        val rightEdgePlacement = placements.firstOrNull { it.index == hours.lastIndex }
        assertTrue("Expected right-edge cloud label to render. placements=$placements", rightEdgePlacement != null)
        assertEquals(
            "Expected right-edge low cloud label to move above the curve when an icon blocks the below position.",
            true,
            rightEdgePlacement!!.placedAbove,
        )
    }

    private fun formatHour(hour24: Int): String {
        val h = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        val suffix = if (hour24 < 12) "a" else "p"
        return "$h$suffix"
    }
}
