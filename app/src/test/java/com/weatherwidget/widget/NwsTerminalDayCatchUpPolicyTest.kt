package com.weatherwidget.widget

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.testutil.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

@Category(ShortDuration::class)
class NwsTerminalDayCatchUpPolicyTest {

    @Test
    fun `isInCatchUpWindow returns true during window`() {
        assertTrue(NwsTerminalDayCatchUpPolicy.isInCatchUpWindow(LocalTime.of(18, 15)))
        assertTrue(NwsTerminalDayCatchUpPolicy.isInCatchUpWindow(LocalTime.of(19, 0)))
        assertTrue(NwsTerminalDayCatchUpPolicy.isInCatchUpWindow(LocalTime.of(19, 29)))
    }

    @Test
    fun `isInCatchUpWindow returns false before window`() {
        assertFalse(NwsTerminalDayCatchUpPolicy.isInCatchUpWindow(LocalTime.of(18, 14)))
        assertFalse(NwsTerminalDayCatchUpPolicy.isInCatchUpWindow(LocalTime.of(12, 0)))
        assertFalse(NwsTerminalDayCatchUpPolicy.isInCatchUpWindow(LocalTime.of(0, 0)))
    }

    @Test
    fun `isInCatchUpWindow returns false at and after window end`() {
        assertFalse(NwsTerminalDayCatchUpPolicy.isInCatchUpWindow(LocalTime.of(19, 30)))
        assertFalse(NwsTerminalDayCatchUpPolicy.isInCatchUpWindow(LocalTime.of(20, 0)))
        assertFalse(NwsTerminalDayCatchUpPolicy.isInCatchUpWindow(LocalTime.of(23, 59)))
    }

    @Test
    fun `shouldScheduleCatchUp requires all three conditions`() {
        assertTrue(NwsTerminalDayCatchUpPolicy.shouldScheduleCatchUp(
            isCharging = true, isScreenInteractive = true, isInWindow = true,
        ))
        assertFalse(NwsTerminalDayCatchUpPolicy.shouldScheduleCatchUp(
            isCharging = false, isScreenInteractive = true, isInWindow = true,
        ))
        assertFalse(NwsTerminalDayCatchUpPolicy.shouldScheduleCatchUp(
            isCharging = true, isScreenInteractive = false, isInWindow = true,
        ))
        assertFalse(NwsTerminalDayCatchUpPolicy.shouldScheduleCatchUp(
            isCharging = true, isScreenInteractive = true, isInWindow = false,
        ))
    }

    @Test
    fun `detectTerminalDayMissingHigh returns null for empty list`() {
        assertNull(NwsTerminalDayCatchUpPolicy.detectTerminalDayMissingHigh(emptyList()))
    }

    @Test
    fun `detectTerminalDayMissingHigh returns null when no NWS forecasts`() {
        val forecasts = listOf(
            TestData.forecast(targetDate = "2026-04-24", source = "OPEN_METEO", highTemp = null, lowTemp = 50f),
        )
        assertNull(NwsTerminalDayCatchUpPolicy.detectTerminalDayMissingHigh(forecasts))
    }

    @Test
    fun `detectTerminalDayMissingHigh returns null when terminal day has high`() {
        val today = LocalDate.of(2026, 4, 17)
        val forecasts = listOf(
            TestData.forecast(targetDate = "2026-04-18", source = "NWS", highTemp = 70f, lowTemp = 50f),
            TestData.forecast(targetDate = "2026-04-24", source = "NWS", highTemp = 65f, lowTemp = 45f),
        )
        assertNull(NwsTerminalDayCatchUpPolicy.detectTerminalDayMissingHigh(forecasts, today))
    }

    @Test
    fun `detectTerminalDayMissingHigh returns info when terminal day has null high and non-null low`() {
        val today = LocalDate.of(2026, 4, 17)
        val forecasts = listOf(
            TestData.forecast(targetDate = "2026-04-18", source = "NWS", highTemp = 70f, lowTemp = 50f),
            TestData.forecast(targetDate = "2026-04-24", source = "NWS", highTemp = null, lowTemp = 45f),
        )
        val result = NwsTerminalDayCatchUpPolicy.detectTerminalDayMissingHigh(forecasts, today)
        assertNotNull(result)
        assertEquals(LocalDate.of(2026, 4, 24), result!!.date)
        assertEquals(45f, result.lowTemp)
    }

    @Test
    fun `detectTerminalDayMissingHigh returns null when terminal day has null low`() {
        val today = LocalDate.of(2026, 4, 17)
        val forecasts = listOf(
            TestData.forecast(targetDate = "2026-04-24", source = "NWS", highTemp = null, lowTemp = null),
        )
        assertNull(NwsTerminalDayCatchUpPolicy.detectTerminalDayMissingHigh(forecasts, today))
    }

    @Test
    fun `detectTerminalDayMissingHigh only checks furthest future NWS day`() {
        val today = LocalDate.of(2026, 4, 17)
        val forecasts = listOf(
            TestData.forecast(targetDate = "2026-04-20", source = "NWS", highTemp = null, lowTemp = 40f),
            TestData.forecast(targetDate = "2026-04-24", source = "NWS", highTemp = 65f, lowTemp = 45f),
        )
        assertNull(NwsTerminalDayCatchUpPolicy.detectTerminalDayMissingHigh(forecasts, today))
    }

    @Test
    fun `detectTerminalDayMissingHigh ignores today and past days`() {
        val today = LocalDate.of(2026, 4, 17)
        val forecasts = listOf(
            TestData.forecast(targetDate = "2026-04-17", source = "NWS", highTemp = null, lowTemp = 45f),
            TestData.forecast(targetDate = "2026-04-16", source = "NWS", highTemp = null, lowTemp = 40f),
        )
        assertNull(NwsTerminalDayCatchUpPolicy.detectTerminalDayMissingHigh(forecasts, today))
    }

    @Test
    fun `computeJitteredDelay returns positive value within expected range`() {
        val random = Random(42)
        repeat(100) {
            val delay = NwsTerminalDayCatchUpPolicy.computeJitteredDelay(baseMinutes = 15, random = random)
            val minExpected = (15 - 3) * 60_000L
            val maxExpected = (15 + 3) * 60_000L
            assertTrue("Delay $delay should be >= $minExpected", delay >= minExpected)
            assertTrue("Delay $delay should be <= $maxExpected", delay <= maxExpected)
        }
    }

    @Test
    fun `computeJitteredDelay coerces to at least 1 minute`() {
        val delay = NwsTerminalDayCatchUpPolicy.computeJitteredDelay(baseMinutes = 0, random = Random(0))
        assertTrue(delay >= 60_000L)
    }

    @Test
    fun `computeInitialDelay returns 0 when already in window`() {
        val delay = NwsTerminalDayCatchUpPolicy.computeInitialDelay(
            now = LocalTime.of(18, 30),
            random = Random.Default,
        )
        assertEquals(0L, delay)
    }

    @Test
    fun `computeInitialDelay returns positive value when before window`() {
        val delay = NwsTerminalDayCatchUpPolicy.computeInitialDelay(
            now = LocalTime.of(17, 0),
            random = Random(0),
        )
        val minMinutes = java.time.Duration.between(LocalTime.of(17, 0), NwsTerminalDayCatchUpPolicy.WINDOW_START).toMinutes()
        val maxMinutes = minMinutes + 5
        assertTrue("Delay should be >= ${minMinutes * 60_000L}", delay >= minMinutes * 60_000L)
        assertTrue("Delay should be <= ${maxMinutes * 60_000L}", delay <= maxMinutes * 60_000L)
    }
}
