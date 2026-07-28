package com.weatherwidget.data.remote

import com.weatherwidget.shared.util.DailySnapshotSelector
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.ZonedDateTime

/**
 * Part 2 of the `-100°F` sentinel work (incident 2026-07-28, plan `260728c`).
 *
 * The 2026-07-27 ingest gate stopped new sentinels, but one row written ~2h *before* that gate
 * landed kept rendering for another day on all three devices. Two follow-ups are pinned here:
 *
 * 1. the read-side guard that neutralises already-stored sentinels, and the selector interaction
 *    that let one reach the renderer on Android in the first place;
 * 2. the relative daily-vs-hourly cross-check, which catches wrong-but-plausible values that the
 *    absolute range gate structurally cannot see.
 *
 * Numbers are taken from the incident so they stay meaningful: the poisoned snapshot carried
 * `high=78, low=-100` for 2026-07-28, while NWS's own hourly series for that day — from the same
 * fetch — reported min 58°F and max 80°F.
 */
@Category(ShortDuration::class)
class NwsHourlyDivergenceTest {

    private val date = "2026-07-28"

    private fun hour(iso: String, tempF: Float): NwsApi.HourlyForecastPeriod {
        val zdt = ZonedDateTime.parse(iso)
        return NwsApi.HourlyForecastPeriod(
            startTime = zdt.toInstant().toEpochMilli(),
            localDate = zdt.toLocalDate().toString(),
            localHour = zdt.hour,
            temperature = tempF,
            shortForecast = "Sunny",
        )
    }

    /** A full 24h of hourly readings for [date] ranging between [min] and [max]. */
    private fun fullDay(min: Float, max: Float): List<NwsApi.HourlyForecastPeriod> =
        (0..23).map { h ->
            val t = when (h) {
                5 -> min      // pre-dawn minimum
                15 -> max     // afternoon maximum
                else -> (min + max) / 2f
            }
            hour("${date}T%02d:00:00-07:00".format(h), t)
        }

    // ── Read-side guard ────────────────────────────────────────────────────────

    @Test
    fun `read guard nulls the sentinel and passes real temperatures through`() {
        assertNull("-100 sentinel must not survive a read", (-100f).orNullIfImplausibleTempF())
        assertEquals(58f, 58f.orNullIfImplausibleTempF())
        assertNull(null.orNullIfImplausibleTempF())
    }

    @Test
    fun `read guard rejects NaN and infinities`() {
        // The KDoc claims null-checks and elvis do not guard against NaN; assert it rather than
        // trust it. NaN would otherwise propagate into roundToInt() and axis math.
        assertNull(Float.NaN.orNullIfImplausibleTempF())
        assertNull(Float.POSITIVE_INFINITY.orNullIfImplausibleTempF())
        assertNull(Float.NEGATIVE_INFINITY.orNullIfImplausibleTempF())
    }

    /**
     * The exact interaction that let a sentinel reach the Android renderer.
     *
     * `selectPriorDaySnapshot` deliberately prefers a candidate older than now-24h, and its callers
     * pre-filter on "both temps present". A raw `-100` is non-null, so it passed that filter and was
     * then *preferred* for being the oldest. With the read guard applied first the low becomes null,
     * the row fails the completeness filter, and the selector falls back — a missing comparison bar
     * instead of a poisoned axis.
     */
    @Test
    fun `guarded snapshot candidate is dropped so the selector falls back`() {
        data class Row(val high: Float?, val low: Float?, val fetchedAt: Long)

        val now = ZonedDateTime.parse("2026-07-28T12:29:00-07:00").toInstant().toEpochMilli()
        val poisoned = Row(78f, -100f, ZonedDateTime.parse("2026-07-27T12:20:10-07:00").toInstant().toEpochMilli())
        val healthy = Row(78f, 58f, ZonedDateTime.parse("2026-07-28T09:00:00-07:00").toInstant().toEpochMilli())

        // Unguarded: the poisoned row is >24h old, passes the completeness filter, and wins.
        val unguarded = listOf(poisoned, healthy).filter { it.high != null && it.low != null }
        assertEquals(
            "precondition: the unguarded path really does select the poisoned row",
            poisoned,
            DailySnapshotSelector.selectPriorDaySnapshot(unguarded, now) { it.fetchedAt },
        )

        // Guarded: the low is nulled, the row fails the completeness filter, selector falls back.
        val guarded = listOf(poisoned, healthy)
            .map { it.copy(high = it.high.orNullIfImplausibleTempF(), low = it.low.orNullIfImplausibleTempF()) }
            .filter { it.high != null && it.low != null }
        assertEquals(healthy, DailySnapshotSelector.selectPriorDaySnapshot(guarded, now) { it.fetchedAt })
    }

    // ── Relative cross-check ───────────────────────────────────────────────────

    @Test
    fun `routine daily-vs-hourly disagreement does not flag`() {
        // Real 2026-07-28 numbers: stored daily high 78, calendar-day hourly max 80. That 2° gap is
        // the day-low-belongs-to-the-night-ending-that-morning convention plus endpoint rounding.
        // If this ever flags, the tolerance has been tightened into uselessness.
        val diverged = NwsDailyMapper.detectHourlyDivergence(
            temperatureMap = mapOf(date to (78f to 58f)),
            hourlyPeriods = fullDay(min = 58f, max = 80f),
        )
        assertTrue("routine 2F divergence must not be flagged, got $diverged", diverged.isEmpty())
    }

    @Test
    fun `sentinel-magnitude divergence is flagged`() {
        val diverged = NwsDailyMapper.detectHourlyDivergence(
            temperatureMap = mapOf(date to (78f to -100f)),
            hourlyPeriods = fullDay(min = 58f, max = 80f),
        )
        assertEquals(1, diverged.size)
        assertEquals(date, diverged[0].dateString)
        assertEquals("the low, not the high, is the bad value", false, diverged[0].isMax)
        assertEquals(-100f, diverged[0].rawValueF)
    }

    @Test
    fun `wrong-but-plausible value is caught even though the absolute gate passes it`() {
        // This is the whole point of the relative check: 20F in July is absurd but sits comfortably
        // inside NwsTemperaturePlausibility's -80..140 bounds.
        assertTrue(
            "precondition: the absolute gate does NOT catch this",
            NwsTemperaturePlausibility.isPlausibleF(20f),
        )
        val diverged = NwsDailyMapper.detectHourlyDivergence(
            temperatureMap = mapOf(date to (78f to 20f)),
            hourlyPeriods = fullDay(min = 58f, max = 80f),
        )
        assertEquals(1, diverged.size)
        assertEquals(20f, diverged[0].rawValueF)
    }

    @Test
    fun `partial hourly coverage is not trusted to judge the daily value`() {
        // Only the warm afternoon hours present: the hourly "min" is 79, which would look like a
        // 21F divergence from a perfectly correct daily low of 58.
        val partial = (12..17).map { h -> hour("${date}T%02d:00:00-07:00".format(h), 79f) }
        assertTrue(partial.size < NwsDailyMapper.MIN_HOURS_FOR_DIVERGENCE_CHECK)

        val diverged = NwsDailyMapper.detectHourlyDivergence(
            temperatureMap = mapOf(date to (78f to 58f)),
            hourlyPeriods = partial,
        )
        assertTrue("a partial day must not condemn a good daily value, got $diverged", diverged.isEmpty())
    }

    @Test
    fun `flagged value is cleared and then repaired from hourly`() {
        val temperatureMap: MutableMap<String, Pair<Float?, Float?>> =
            mutableMapOf(date to (78f as Float? to -100f as Float?))
        val hourly = fullDay(min = 58f, max = 80f)

        val diverged = NwsDailyMapper.detectHourlyDivergence(temperatureMap, hourly)
        assertEquals(1, diverged.size)

        // The repair only fills nulls, so a value rejected by the relative check must be cleared
        // first — the absolute gate's rejections never got written in the first place.
        NwsDailyMapper.clearRejectedTemps(temperatureMap, diverged)
        assertNull("clear must null the low", temperatureMap.getValue(date).second)
        assertEquals("clear must not disturb the good high", 78f, temperatureMap.getValue(date).first)

        val repairs = NwsDailyMapper.fillTemperatureGapsFromHourly(temperatureMap, diverged, hourly)
        assertEquals(1, repairs.size)
        assertEquals(
            "low must be repaired to the hourly minimum",
            58f,
            temperatureMap.getValue(date).second,
        )
        assertEquals(78f, temperatureMap.getValue(date).first)
    }

    @Test
    fun `healthy day survives the whole pipeline untouched`() {
        val temperatureMap: MutableMap<String, Pair<Float?, Float?>> =
            mutableMapOf(date to (78f as Float? to 58f as Float?))
        val hourly = fullDay(min = 58f, max = 80f)

        val diverged = NwsDailyMapper.detectHourlyDivergence(temperatureMap, hourly)
        NwsDailyMapper.clearRejectedTemps(temperatureMap, diverged)
        NwsDailyMapper.fillTemperatureGapsFromHourly(temperatureMap, diverged, hourly)

        assertEquals(78f to 58f, temperatureMap.getValue(date))
    }
}
