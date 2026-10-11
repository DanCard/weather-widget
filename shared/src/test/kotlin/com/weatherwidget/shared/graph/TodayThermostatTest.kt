package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TodayThermostatTest {

    @Test
    fun earlierPeakAboveCurrentIsGhostNotMercury() {
        // Current 62, peak so far 70: the solid mercury stops at 62; 62→70 is the translucent ghost.
        val s = TodayThermostat.segments(barTopHigh = 62f, solidLow = 50f, ghostHigh = 70f)
        assertEquals(62f, s.mercuryTop)
        assertEquals(70f, s.ghostTop)
        assertEquals(50f, s.mercuryBottom)
        assertEquals(70f, s.top)
    }

    @Test
    fun noGhostWhenCurrentIsThePeak() {
        val s = TodayThermostat.segments(barTopHigh = 70f, solidLow = 50f, ghostHigh = 70f)
        assertNull(s.ghostTop)
        assertEquals(70f, s.top)
    }

    @Test
    fun noGhostWithoutObservedPeak() {
        val s = TodayThermostat.segments(barTopHigh = 75f, solidLow = 50f, ghostHigh = null)
        assertNull(s.ghostTop)
        assertEquals(75f, s.mercuryTop)
    }

    @Test
    fun bulbNeverWarmerThanMercuryTop() {
        // Forecast-only source: pre-dawn current 45 under the forecast low 48.
        val s = TodayThermostat.segments(barTopHigh = 45f, solidLow = 48f, ghostHigh = null)
        assertEquals(45f, s.mercuryBottom)
    }

    @Test
    fun nothingKnown() {
        val s = TodayThermostat.segments(barTopHigh = null, solidLow = null, ghostHigh = null)
        assertNull(s.mercuryTop)
        assertNull(s.mercuryBottom)
        assertNull(s.top)
    }

    @Test
    fun ghostAlphaMatchesAndroid() {
        assertEquals(75, TodayThermostat.GHOST_ALPHA_255)
        assertEquals(75 / 255f, TodayThermostat.GHOST_ALPHA, 0f)
    }
}
