package com.weatherwidget.widget

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.DisplayMetrics
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category



/**
 * Simple state-based test for fetch dot label colors.
 * Verifies that the colors reported via FetchDotDebug match the expected yellow.
 */
@RunWith(RobolectricTestRunner::class)
@Category(MediumDuration::class)
class TemperatureFetchDotColorTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `fetch dot labels use actual line yellow color`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 3, 23, 10, 0)
        // Need at least 2 points to have a range
        val hours = listOf(
            TemperatureGraphRenderer.HourData(start, 70f, "10a", isCurrentHour = true),
            TemperatureGraphRenderer.HourData(start.plusHours(1), 72f, "11a")
        )
        // observedAt must be within the range [start, start+1h]
        val observedAtMs = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        var debugResult: TemperatureGraphRenderer.FetchDotDebug? = null

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 400,
            heightPx = 200,
            currentTime = start.plusMinutes(15),
            observedAt = observedAtMs,
            lastObservedTemp = 70f,
            onFetchDotResolved = { debugResult = it }
        )

        val expectedValueColor = Color.parseColor("#BBF4C542")
        val expectedStalenessColor = Color.parseColor("#88F4C542")

        org.junit.Assert.assertNotNull("Fetch dot should have been resolved", debugResult)
        assertEquals("Value label color should match actual line yellow (with alpha)", expectedValueColor, debugResult?.valueColor)
        assertEquals("Staleness label color should match actual line yellow (with alpha)", expectedStalenessColor, debugResult?.stalenessColor)
    }

    private fun mockContext(): Context {
        val metrics = DisplayMetrics().apply { density = 1.0f }
        val resources = mockk<Resources>(relaxed = true)
        every { resources.displayMetrics } returns metrics
        val context = mockk<Context>(relaxed = true)
        every { context.resources } returns resources
        return context
    }
}
