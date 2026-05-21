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
    fun `right edge low cloud label is placed clear of the inline footer icon`() {
        // The inline footer renders each labeled hour as <hour><icon><a|p>. A right-edge low-cloud
        // % label must still be placed (above or below, depending on icon size/padding tuning) —
        // i.e. the collision logic finds a slot that clears the icon rather than dropping the
        // label. Whether it lands above or below is a tuning detail, so we don't pin it here.
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
        assertTrue(
            "Expected right-edge low cloud label to be placed clear of the footer icon. placements=$placements",
            rightEdgePlacement != null,
        )
    }

    @Test
    fun `wide widget injects middle label for monotone data`() {
        val start = LocalDateTime.of(2026, 5, 5, 9, 0)
        // 12 hours of 100% cloud cover (monotone)
        val hours = (0 until 12).map { index ->
            val dateTime = start.plusHours(index.toLong())
            CloudCoverGraphRenderer.CloudHourData(
                dateTime = dateTime,
                cloudCover = 100,
                label = "${index + 9}a",
                showLabel = true,
                isCurrentHour = index == 0,
            )
        }
        val placements = mutableListOf<CloudCoverGraphRenderer.LabelPlacementDebug>()

        // Wide widget (5 columns)
        CloudCoverGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 300,
            currentTime = start,
            numColumns = 5,
            onLabelPlaced = { placements.add(it) },
        )

        // For monotone 100% data, it would normally only label start (0) and end (11).
        // With numColumns=5, it should inject midpoint (5).
        val indices = placements.map { it.index }.sorted()
        assertEquals("Expected 3 labels for 5-column wide widget with monotone data", listOf(0, 5, 11), indices)
    }

    @Test
    fun `narrow widget does not inject middle label for monotone data`() {
        val start = LocalDateTime.of(2026, 5, 5, 9, 0)
        val hours = (0 until 12).map { index ->
            val dateTime = start.plusHours(index.toLong())
            CloudCoverGraphRenderer.CloudHourData(
                dateTime = dateTime,
                cloudCover = 100,
                label = "${index + 9}a",
                showLabel = true,
                isCurrentHour = index == 0,
            )
        }
        val placements = mutableListOf<CloudCoverGraphRenderer.LabelPlacementDebug>()

        // Narrow widget (4 columns)
        CloudCoverGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 400,
            heightPx = 300,
            currentTime = start,
            numColumns = 4,
            onLabelPlaced = { placements.add(it) },
        )

        val indices = placements.map { it.index }.sorted()
        assertEquals("Expected only edge labels for 4-column widget with monotone data", listOf(0, 11), indices)
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
