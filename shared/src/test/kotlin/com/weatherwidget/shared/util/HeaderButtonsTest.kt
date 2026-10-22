package com.weatherwidget.shared.util

import com.weatherwidget.shared.util.HeaderButton.GRAPH_SELECTOR
import com.weatherwidget.shared.util.HeaderButton.HISTORY
import com.weatherwidget.shared.util.HeaderButton.HOME
import com.weatherwidget.shared.util.HeaderButton.MONTHS
import com.weatherwidget.shared.util.HeaderButton.OBSERVATIONS
import com.weatherwidget.shared.util.HeaderFit.Measured
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime

/** Home first, observations second, history last (user, 2026-10-22). `plans/261022-home-button-first-shared-header-buttons.md`. */
@Category(ShortDuration::class)
class HeaderButtonsTest {

    private fun measured(want: HeaderButtons.Want, width: Float = 2000f) = Measured(
        width = width,
        rightCluster = 60f,
        gap = 6f,
        seamOffset = 20f,
        temp = 60f,
        icon = 30f,
        date = if (want.hourly) 0f else 46f,
        baseButtons = want.baseButtons,
        wantHome = want.wantHome,
        wantMonths = want.wantMonths,
        centreButton = 30f,
        centreButtonSpacing = 10f,
    )

    private fun layout(want: HeaderButtons.Want, width: Float = 2000f): Map<HeaderButton, Float> {
        val m = measured(want, width)
        val plan = HeaderFit.resolve(m)
        val shown = HeaderButtons.shown(want, plan)
        assertEquals("shown matches the fit's count: $plan", plan.buttons, shown.size)
        val (left, _) = HeaderFit.buttonsSpan(m, plan) ?: return emptyMap()
        return shown.withIndex().associate { (i, b) -> b to left + i * (m.centreButton + m.centreButtonSpacing) }
    }

    @Test
    fun `home is first and history last`() {
        assertEquals(HOME, HeaderButtons.ORDER.first())
        assertEquals(HISTORY, HeaderButtons.ORDER.last())
    }

    @Test
    fun `daily shows home, observations, months, history in that order`() {
        val all = HeaderButtons.daily(observationsInView = true, home = true, months = true)
        assertEquals(listOf(HOME, OBSERVATIONS, MONTHS, HISTORY), layout(all).keys.toList())
        val noObs = HeaderButtons.daily(observationsInView = false, home = true, months = false)
        assertEquals(listOf(HOME, HISTORY), layout(noObs).keys.toList())
    }

    @Test
    fun `home appearing on a source switch does not move observations or anything after it`() {
        val atHome = layout(HeaderButtons.daily(observationsInView = true, home = false, months = true))
        val away = layout(HeaderButtons.daily(observationsInView = true, home = true, months = true))
        assertEquals(listOf(OBSERVATIONS, MONTHS, HISTORY), atHome.keys.toList())
        for (b in atHome.keys) assertEquals("$b", atHome.getValue(b), away.getValue(b), 0.01f)
    }

    @Test
    fun `hourly shows home, observations, graph selector, history, observations only with today on screen`() {
        assertEquals(listOf(HOME, OBSERVATIONS, GRAPH_SELECTOR, HISTORY), layout(HeaderButtons.hourly(true)).keys.toList())
        assertEquals(listOf(HOME, GRAPH_SELECTOR, HISTORY), layout(HeaderButtons.hourly(false)).keys.toList())
    }

    @Test
    fun `with four buttons each, home, observations and history sit at the same x on daily and hourly`() {
        val daily = layout(HeaderButtons.daily(observationsInView = true, home = true, months = true))
        val hourly = layout(HeaderButtons.hourly(true))
        for (b in listOf(HOME, OBSERVATIONS, HISTORY)) assertEquals("$b", daily.getValue(b), hourly.getValue(b), 0.01f)
    }

    @Test
    fun `nothing is shown that the fit dropped`() {
        val want = HeaderButtons.daily(observationsInView = true, home = true, months = true)
        // Room for the two base buttons but not home or months.
        val plan = HeaderFit.Plan(buttons = 2, placement = HeaderFit.ButtonPlacement.CENTER)
        assertEquals(listOf(OBSERVATIONS, HISTORY), HeaderButtons.shown(want, plan))
        assertEquals(emptyList<HeaderButton>(), HeaderButtons.shown(want, HeaderFit.Plan()))
        // A header too narrow for any button.
        assertEquals(emptyMap<HeaderButton, Float>(), layout(want, width = 150f))
    }

    @Test
    fun `hourly observations follow whether the window shows any of today`() {
        val today = LocalDate.of(2026, 10, 22)
        val midnight = today.atStartOfDay()
        fun inView(start: LocalDateTime, end: LocalDateTime) = HeaderButtons.hourlyObservationsInView(start, end, today)
        assertTrue("today", inView(midnight.plusHours(6), midnight.plusHours(18)))
        assertTrue("tonight into tomorrow", inView(midnight.plusHours(20), midnight.plusHours(30)))
        assertTrue("last night into this morning", inView(midnight.minusHours(4), midnight.plusHours(2)))
        assertFalse("yesterday", inView(midnight.minusHours(18), midnight.minusHours(6)))
        assertFalse("ends at today's 00:00", inView(midnight.minusHours(12), midnight))
        assertFalse("tomorrow", inView(midnight.plusHours(24), midnight.plusHours(36)))
    }

    @Test
    fun `graph selector cycles temperature, cloud, precipitation and names the next view`() {
        assertEquals(ViewMode.CLOUD_COVER, GraphSelector.next(ViewMode.TEMPERATURE))
        assertEquals(ViewMode.PRECIPITATION, GraphSelector.next(ViewMode.CLOUD_COVER))
        assertEquals(ViewMode.TEMPERATURE, GraphSelector.next(ViewMode.PRECIPITATION))
        assertEquals("☁️", GraphSelector.emoji(ViewMode.TEMPERATURE))
        assertEquals("🌧️", GraphSelector.emoji(ViewMode.CLOUD_COVER))
        assertEquals("🌡️", GraphSelector.emoji(ViewMode.PRECIPITATION))
    }
}
