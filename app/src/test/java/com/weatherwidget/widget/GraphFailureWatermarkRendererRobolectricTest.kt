package com.weatherwidget.widget

import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class GraphFailureWatermarkRendererRobolectricTest {
    @Test
    fun `long watermark text fits inside a narrow canvas and pill`() {
        val density = 1f
        val layout =
            layout(
                width = 90f,
                height = 100f,
                density = density,
                source = "Extremely Long Weather Provider",
                errorCode = "CONN_REFUSED",
            )

        assertNotNull(layout)
        layout!!
        assertTrue(layout.pillBounds.left >= 0f)
        assertTrue(layout.pillBounds.right <= 90f)
        assertTrue(layout.pillBounds.top >= 0f)
        assertTrue(layout.pillBounds.bottom <= 100f)
        val innerWidth = layout.pillBounds.width() - 24f * density
        assertTrue(measure(layout.mainText, layout.mainTextSize) <= innerWidth + 0.01f)
        if (layout.detailText != null && layout.detailTextSize != null) {
            assertTrue(measure(layout.detailText, layout.detailTextSize) <= innerWidth + 0.01f)
        }
    }

    @Test
    fun `non-positive and too-small canvases decline layout`() {
        assertNull(layout(width = 0f, height = 100f))
        assertNull(layout(width = 100f, height = 1f))
    }

    @Test
    fun `error and failure time formatting remain deterministic`() {
        val zone = ZoneId.of("America/Los_Angeles")
        val now =
            LocalDateTime.of(2026, 7, 29, 16, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        val sameDay =
            LocalDateTime.of(2026, 7, 29, 9, 30)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        val older =
            LocalDateTime.of(2026, 7, 28, 9, 30)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()

        assertEquals("429 Rate Limited", GraphFailureWatermarkRenderer.humanReadableErrorCode("HTTP_429"))
        assertEquals(
            "9:30 AM",
            GraphFailureWatermarkRenderer.formatFailureTime(
                sameDay,
                nowMs = now,
                locale = Locale.US,
                zoneId = zone,
            ),
        )
        assertEquals(
            "Jul 28, 9:30 AM",
            GraphFailureWatermarkRenderer.formatFailureTime(
                older,
                nowMs = now,
                locale = Locale.US,
                zoneId = zone,
            ),
        )
    }

    private fun layout(
        width: Float,
        height: Float,
        density: Float = 1f,
        source: String? = "Silurian",
        errorCode: String? = "TIMEOUT",
    ): FailureWatermarkLayout? =
        GraphFailureWatermarkRenderer.calculateLayout(
            width = width,
            height = height,
            density = density,
            sourceLabel = source,
            errorCode = errorCode,
            failureTimeMs = null,
            measureMain = ::measure,
            measureDetail = ::measure,
            mainMetrics = ::metrics,
            detailMetrics = ::metrics,
        )

    private fun measure(
        text: String,
        textSize: Float,
    ): Float = text.length * textSize * 0.5f

    private fun metrics(textSize: Float): Pair<Float, Float> =
        -textSize * 0.8f to textSize * 0.2f
}
