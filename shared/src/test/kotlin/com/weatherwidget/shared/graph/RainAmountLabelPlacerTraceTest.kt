package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.PrecipTrace
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.Locale

/**
 * The precip graph's "Act" label names a trace-only period "Trace"; the "Pred" label never does.
 * Both platforms place their labels here (plans/261021-observed-rain-trace.md).
 */
@Category(ShortDuration::class)
class RainAmountLabelPlacerTraceTest {

    private val originalLocale = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun labelFor(amountMm: Float, prefix: String, observed: Boolean): String {
        Locale.setDefault(Locale.US)
        return RainAmountLabelPlacer.calculatePlacements(
            rainPeriods = listOf(RainPeriodSelection.RainPeriod(startIndex = 0, endIndex = 0, totalAmountMm = amountMm)),
            widthPx = 400f,
            graphTop = 0f,
            graphBottom = 200f,
            graphHeight = 200f,
            initialCollisionBounds = emptyList(),
            labelPrefix = prefix,
            observed = observed,
            measureText = { it.length * 6f },
            getTextBounds = { -5f to 5f },
            paddingPx = 2f,
        ).single().text
    }

    @Test
    fun `an observed trace reads Act Trace`() {
        assertEquals("Act Trace", labelFor(PrecipTrace.MM, "Act ", observed = true))
    }

    @Test
    fun `a forecast of the same size keeps its amount`() {
        assertEquals("Pred <.001in", labelFor(PrecipTrace.MM, "Pred ", observed = false))
    }

    @Test
    fun `an observed measurable amount is unchanged`() {
        assertEquals("Act .05in", labelFor(1.27f, "Act ", observed = true))
    }
}
