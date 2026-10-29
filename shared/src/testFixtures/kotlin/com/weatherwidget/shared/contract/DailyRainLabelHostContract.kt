package com.weatherwidget.shared.contract

import com.weatherwidget.shared.graph.DailyRainLabelLayout
import com.weatherwidget.shared.graph.DailyRainLabelLayout.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a [DailyRainLabelLayout.Host] measurement means, on Android's Paint host and desktop's Compose
 * TextMeasurer host (plans/261029-shared-daily-rain-labels.md). The shared runner places boxes from
 * these numbers, so a host whose baseline or scale meant something else would shift labels on one
 * platform only.
 */
abstract class DailyRainLabelHostContract {
    /** The host the platform's daily graph draws with, at label scale 1. */
    protected abstract fun host(): DailyRainLabelLayout.Host

    @Test
    fun `the baseline sits inside the box, below its top`() {
        Kind.entries.forEach { kind ->
            val size = host().measure("40%", kind, 1f)
            assertTrue("$kind: $size", size.height > 0f && size.baseline > 0f && size.baseline <= size.height)
        }
    }

    @Test
    fun `longer text measures wider`() {
        Kind.entries.forEach { kind ->
            assertTrue("$kind", host().measure("100%", kind, 1f).width > host().measure("5%", kind, 1f).width)
        }
    }

    @Test
    fun `font scale scales width and height`() {
        Kind.entries.forEach { kind ->
            val one = host().measure("40%", kind, 1f)
            val two = host().measure("40%", kind, 2f)
            assertEquals("$kind width", 2f, two.width / one.width, 0.25f)
            assertEquals("$kind height", 2f, two.height / one.height, 0.25f)
        }
    }

    @Test
    fun `the peak hour shares the day chance's base size`() {
        assertEquals(host().measure("1pm", Kind.DAY, 0.75f), host().measure("1pm", Kind.PEAK, 0.75f))
    }
}
