package com.weatherwidget.widget

import android.graphics.PathMeasure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

/**
 * Verifies that per-segment forecast paths produced by [AndroidCurvePathBuilder]
 * have measurable length so that a cumulative-phase [android.graphics.DashPathEffect] can
 * produce visible dashes even on flat (past) segments.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class TemperatureGraphDashContinuityTest {

    @Test
    fun `flat segments have positive path length for dash phase accumulation`() {
        // Simulate a flat past curve: 10 points at y=100, evenly spaced 30px apart
        val flatPoints = (0..9).map { i -> (i * 30f) to 100f }
        val segments = AndroidCurvePathBuilder.buildPerSegmentPaths(flatPoints)

        assertTrue("Expected 9 segments", segments.size == 9)

        var cumulative = 0f
        for ((i, segment) in segments.withIndex()) {
            val length = PathMeasure(segment.path, false).length
            assertTrue("Segment $i should have positive length, got $length", length > 0f)
            cumulative += length
        }
        assertTrue("Cumulative length should exceed a single dash (8dp)", cumulative > 8f)
    }

    @Test
    fun `cumulative length increases monotonically across segments`() {
        // Simulate a curve with slight slope (past-like conditions)
        val points = (0..12).map { i -> (i * 25f) to (200f - i * 2f) }
        val segments = AndroidCurvePathBuilder.buildPerSegmentPaths(points)

        val lengths = segments.map { PathMeasure(it.path, false).length }
        val cumulativeLengths = lengths.runningFold(0f) { acc, len -> acc + len }

        for (i in 1 until cumulativeLengths.size) {
            assertTrue(
                "Cumulative length should increase at index $i",
                cumulativeLengths[i] > cumulativeLengths[i - 1]
            )
        }
    }

    @Test
    fun `steep segments produce longer paths than flat segments`() {
        val flatPoints = (0..5).map { i -> (i * 30f) to 100f }
        val steepPoints = (0..5).map { i -> (i * 30f) to (100f + i * 40f) }

        val flatLengths = AndroidCurvePathBuilder.buildPerSegmentPaths(flatPoints)
            .map { PathMeasure(it.path, false).length }
        val steepLengths = AndroidCurvePathBuilder.buildPerSegmentPaths(steepPoints)
            .map { PathMeasure(it.path, false).length }

        val flatTotal = flatLengths.sum()
        val steepTotal = steepLengths.sum()

        assertTrue(
            "Steep curve ($steepTotal) should be longer than flat curve ($flatTotal)",
            steepTotal > flatTotal
        )
    }

    @Test
    fun `segments retain source indices across a missing forecast gap`() {
        val points =
            listOf(
                0f to 100f,
                10f to 90f,
                20f to Float.NaN,
                30f to 80f,
                40f to 70f,
            )
        val segments = AndroidCurvePathBuilder.buildPerSegmentPaths(points)

        assertEquals(listOf(0 to 1, 3 to 4), segments.map { it.startPointIndex to it.endPointIndex })
        assertEquals(listOf(true, true), segments.map { it.startsContour })
    }
}
