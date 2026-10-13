package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.util.SunPositionUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@Category(ShortDuration::class)
class TodayLowSettleTest {

    private val pacific = ZoneId.of("America/Los_Angeles")
    private val mvLat = 37.4166
    private val mvLon = -122.0889
    private val oct13 = LocalDate.of(2026, 10, 13)

    private data class Hour(val ms: Long, val temp: Float)

    private fun hour(t: LocalDateTime, temp: Float) = Hour(t.atZone(pacific).toInstant().toEpochMilli(), temp)

    private fun decide(now: LocalDateTime, actualLow: Float?, hourly: List<Hour>, lat: Double? = mvLat, lon: Double? = mvLon) =
        TodayLowSettle.decide(now, lat, lon, pacific, actualLow, hourly, { it.ms }, { it.temp })

    private fun assertNear(expected: LocalTime, actual: LocalTime?, toleranceMin: Long = 3) {
        val diff = Duration.between(expected, actual!!).abs().toMinutes()
        assertTrue("expected ~$expected, got $actual", diff <= toleranceMin)
    }

    // ── Cutoff = sunrise + 30 min (published Bay Area sunrises, ±3 min) ─────

    @Test
    fun cutoffTodayIsSunrisePlus30() {
        // Sunrise 07:14 PDT on 2026-10-13.
        assertNear(LocalTime.of(7, 44), TodayLowSettle.sunriseCutoff(oct13, mvLat, mvLon, pacific)?.toLocalTime())
    }

    @Test
    fun cutoffFollowsTheSeason() {
        // June solstice sunrise 05:48 PDT; December solstice 07:19 PST.
        assertNear(LocalTime.of(6, 18), TodayLowSettle.sunriseCutoff(LocalDate.of(2026, 6, 21), mvLat, mvLon, pacific)?.toLocalTime())
        assertNear(LocalTime.of(7, 49), TodayLowSettle.sunriseCutoff(LocalDate.of(2026, 12, 21), mvLat, mvLon, pacific)?.toLocalTime())
    }

    @Test
    fun cutoffJumpsAnHourAcrossDstChanges() {
        // Spring forward (2026-03-08): 06:31 PST → 07:30 PDT. Fall back (2026-11-01): 07:32 PDT → 06:33 PST.
        assertNear(LocalTime.of(7, 1), TodayLowSettle.sunriseCutoff(LocalDate.of(2026, 3, 7), mvLat, mvLon, pacific)?.toLocalTime())
        assertNear(LocalTime.of(8, 0), TodayLowSettle.sunriseCutoff(LocalDate.of(2026, 3, 8), mvLat, mvLon, pacific)?.toLocalTime())
        assertNear(LocalTime.of(8, 2), TodayLowSettle.sunriseCutoff(LocalDate.of(2026, 10, 31), mvLat, mvLon, pacific)?.toLocalTime())
        assertNear(LocalTime.of(7, 3), TodayLowSettle.sunriseCutoff(LocalDate.of(2026, 11, 1), mvLat, mvLon, pacific)?.toLocalTime())
    }

    @Test
    fun sunriseFarEastLandsOnThePreviousUtcDay() {
        // Auckland sunrise 2026-10-13 ≈ 06:41 NZDT = 17:41 UTC on the 12th.
        val sunrise = SunPositionUtils.sunriseInstant(oct13, -36.85, 174.76)!!.atOffset(ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 10, 12), sunrise.toLocalDate())
        assertNear(LocalTime.of(17, 41), sunrise.toLocalTime())
    }

    @Test
    fun noLocationOrPolarFallsBackToNine() {
        assertNull(TodayLowSettle.sunriseCutoff(oct13, null, null, pacific))
        assertNull(TodayLowSettle.sunriseCutoff(LocalDate.of(2026, 12, 21), 85.0, 0.0, pacific)) // polar night
        val d = decide(oct13.atTime(8, 30), actualLow = 50f, hourly = emptyList(), lat = null, lon = null)
        assertEquals(LocalTime.of(9, 0), d.cutoff.toLocalTime())
        assertFalse(d.fromSunrise)
        assertEquals(TodayLowSettle.Reason.BEFORE_CUTOFF, d.reason)
    }

    // ── Decision ─────────────────────────────────────────────────────────

    private val risingDay = (8..23).map { hour(oct13.atTime(it, 0), 52f + it) }

    @Test
    fun beforeCutoffNotSettled() {
        val d = decide(oct13.atTime(7, 30), actualLow = 50f, hourly = risingDay)
        assertFalse(d.settled)
        assertEquals(TodayLowSettle.Reason.BEFORE_CUTOFF, d.reason)
    }

    @Test
    fun afterCutoffWithForecastRisingSettled() {
        val d = decide(oct13.atTime(7, 50), actualLow = 50f, hourly = risingDay)
        assertTrue(d.settled)
        assertTrue(d.fromSunrise)
    }

    @Test
    fun dipWithinTheLookaheadKeepsTheBlend() {
        // 07:50, a cold 10:00 (inside 3 h) at 47 < observed 50: the morning is not done falling.
        val dip = risingDay.map { if (it.ms == hour(oct13.atTime(10, 0), 0f).ms) it.copy(temp = 47f) else it }
        val d = decide(oct13.atTime(7, 50), actualLow = 50f, hourly = dip)
        assertFalse(d.settled)
        assertEquals(TodayLowSettle.Reason.FORECAST_FALLS, d.reason)
        assertEquals(47f, d.lookaheadForecastMin)
    }

    @Test
    fun eveningColdBeyondTheLookaheadDoesNotBlock() {
        // Side-agent case: after a warm night the evening is forecast below the morning low.
        // It is outside the 3 h look-ahead, so the morning still settles at sunrise + 30.
        val evening = risingDay.map { if (it.ms >= hour(oct13.atTime(20, 0), 0f).ms) it.copy(temp = 46f) else it }
        assertTrue(decide(oct13.atTime(7, 50), actualLow = 50f, hourly = evening).settled)
    }

    @Test
    fun nineSettlesWhateverTheForecast() {
        val dip = risingDay.map { if (it.ms == hour(oct13.atTime(10, 0), 0f).ms) it.copy(temp = 47f) else it }
        val d = decide(oct13.atTime(9, 0), actualLow = 50f, hourly = dip)
        assertTrue(d.settled)
        assertEquals(TodayLowSettle.Reason.SETTLED_AT_NINE, d.reason)
    }

    @Test
    fun sunriseAfterNineIsCappedAtNine() {
        // Reykjavík in December: sunrise ≈ 11:20, so 09:00 is the cutoff.
        val reykjavik = ZoneId.of("Atlantic/Reykjavik")
        val day = LocalDate.of(2026, 12, 21)
        val d = TodayLowSettle.decide(day.atTime(9, 0), 64.15, -21.94, reykjavik, 30f, emptyList<Hour>(), { it.ms }, { it.temp })
        assertEquals(LocalTime.of(9, 0), d.cutoff.toLocalTime())
        assertFalse(d.fromSunrise)
        assertTrue(d.settled)
    }

    @Test
    fun forecastTieWithObservedLowIsSettled() {
        val d = decide(oct13.atTime(7, 50), actualLow = 50f, hourly = risingDay + hour(oct13.atTime(9, 30), 50f))
        assertTrue(d.settled)
    }

    @Test
    fun passedHoursDoNotCount() {
        // A cold 06:00 already happened; it is not ahead of 07:50.
        val hourly = risingDay + hour(oct13.atTime(6, 0), 45f)
        assertTrue(decide(oct13.atTime(7, 50), actualLow = 50f, hourly = hourly).settled)
    }

    @Test
    fun noHoursInTheLookaheadSettlesByTimeAlone() {
        assertTrue(decide(oct13.atTime(7, 50), actualLow = 50f, hourly = emptyList()).settled)
    }

    @Test
    fun noActualNeverSettles() {
        val d = decide(oct13.atTime(12, 0), actualLow = null, hourly = risingDay) // even past 09:00
        assertFalse(d.settled)
        assertEquals(TodayLowSettle.Reason.NO_ACTUAL, d.reason)
    }

    @Test
    fun settledDecisionPrintsTheObservedLow() {
        // Warm night: observed 50 vs forecast 47. Before the cutoff the forecast prints; after, the actual.
        fun printed(now: LocalDateTime) = DailyDayValueResolver.effectiveLowForLabel(
            isToday = true, solidLow = 50f, forecastLow = 47f,
            lowSettled = decide(now, actualLow = 50f, hourly = risingDay).settled, actualLow = 50f,
        )
        assertEquals(47f, printed(oct13.atTime(7, 30)))
        assertEquals(50f, printed(oct13.atTime(7, 50)))
    }
}
