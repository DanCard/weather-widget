package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Regression tests for the NWS `-100°F` missing-data sentinel (incident 2026-07-27).
 *
 * NWS served the sentinel simultaneously on both paths that feed the daily low: as
 * `temperature: -100` on the "Tonight" period of `/forecast`, and as `-73.33333333333333` degC —
 * exactly -100.0°F — in the `/gridpoints` minTemperature series. Because both merges only fill
 * nulls, filtering one path alone lets the other supply identical garbage, so these tests pin the
 * behaviour of *both* plus the hourly repair.
 *
 * Real values from the incident are used throughout so the numbers stay meaningful: tomorrow's high
 * was a legitimate 78°F, and the true overnight low (per NWS's own untainted hourly series) 59°F.
 */
@Category(ShortDuration::class)
class NwsSentinelTemperatureTest {

    private val today = LocalDate.parse("2026-07-27")

    private fun period(name: String, start: String, end: String, temp: Int, isDaytime: Boolean) =
        NwsApi.ForecastPeriod(
            name = name,
            startTime = start,
            endTime = end,
            temperature = temp,
            temperatureUnit = "F",
            shortForecast = if (isDaytime) "Sunny" else "Clear",
            isDaytime = isDaytime,
        )

    private fun hour(iso: String, tempF: Float): NwsApi.HourlyForecastPeriod {
        val zdt = ZonedDateTime.parse(iso)
        return NwsApi.HourlyForecastPeriod(
            startTime = zdt.toInstant().toEpochMilli(),
            localDate = zdt.toLocalDate().toString(),
            localHour = zdt.hour,
            temperature = tempF,
            shortForecast = "Clear",
        )
    }

    /** The exact hourly series NWS served for the Tonight window; its minimum is 59°F. */
    private val tonightHours = listOf(
        hour("2026-07-27T18:00:00-07:00", 74f),
        hour("2026-07-27T19:00:00-07:00", 70f),
        hour("2026-07-27T20:00:00-07:00", 67f),
        hour("2026-07-27T21:00:00-07:00", 64f),
        hour("2026-07-27T22:00:00-07:00", 62f),
        hour("2026-07-27T23:00:00-07:00", 61f),
        hour("2026-07-28T00:00:00-07:00", 61f),
        hour("2026-07-28T01:00:00-07:00", 60f),
        hour("2026-07-28T02:00:00-07:00", 59f),
        hour("2026-07-28T03:00:00-07:00", 59f),
        hour("2026-07-28T04:00:00-07:00", 59f),
        hour("2026-07-28T05:00:00-07:00", 59f),
        // Next day warms again — proves the repair honours the period window, not the calendar day.
        hour("2026-07-28T14:00:00-07:00", 78f),
        hour("2026-07-28T22:00:00-07:00", 58f),
    )

    private val tonightPeriod =
        period("Tonight", "2026-07-27T18:00:00-07:00", "2026-07-28T06:00:00-07:00", -100, false)

    private val tuesdayPeriod =
        period("Tuesday", "2026-07-28T06:00:00-07:00", "2026-07-28T18:00:00-07:00", 78, true)

    @Test
    fun `plausibility gate rejects the sentinel and accepts real extremes`() {
        assertTrue("real overnight low must pass", NwsTemperaturePlausibility.isPlausibleF(59f))
        assertTrue("real high must pass", NwsTemperaturePlausibility.isPlausibleF(78f))
        assertTrue("US record low must pass", NwsTemperaturePlausibility.isPlausibleF(-79.8f))

        assertTrue("sentinel must be rejected", !NwsTemperaturePlausibility.isPlausibleF(-100f))
        assertTrue("NaN must be rejected", !NwsTemperaturePlausibility.isPlausibleF(Float.NaN))
        assertTrue(
            "infinity must be rejected",
            !NwsTemperaturePlausibility.isPlausibleF(Float.NEGATIVE_INFINITY),
        )
    }

    @Test
    fun `boundary values are inclusive`() {
        assertTrue(NwsTemperaturePlausibility.isPlausibleF(NwsTemperaturePlausibility.MIN_PLAUSIBLE_F))
        assertTrue(NwsTemperaturePlausibility.isPlausibleF(NwsTemperaturePlausibility.MAX_PLAUSIBLE_F))
        assertTrue(!NwsTemperaturePlausibility.isPlausibleF(NwsTemperaturePlausibility.MIN_PLAUSIBLE_F - 0.1f))
        assertTrue(!NwsTemperaturePlausibility.isPlausibleF(NwsTemperaturePlausibility.MAX_PLAUSIBLE_F + 0.1f))
    }

    @Test
    fun `sentinel period temperature is rejected instead of becoming tomorrows low`() {
        val acc = NwsDailyMapper.NwsDayAccumulator()
        NwsDailyMapper.applyForecastPeriods(
            listOf(tonightPeriod, tuesdayPeriod), today.toString(), acc,
        )

        val (high, low) = acc.temperatureMap["2026-07-28"] ?: (null to null)
        assertEquals("legitimate high must survive", 78f, high)
        assertNull("sentinel must not become the low", low)
        assertEquals(1, acc.rejectedTemps.size)
        assertEquals(-100f, acc.rejectedTemps.first().rawValueF)
        assertEquals("FCST:Tonight", acc.rejectedTemps.first().origin)
    }

    @Test
    fun `rejected period keeps its condition and precip data`() {
        val acc = NwsDailyMapper.NwsDayAccumulator()
        val tonightWithRain = tonightPeriod.copy(precipProbability = 40, shortForecast = "Rainy")
        NwsDailyMapper.applyForecastPeriods(
            listOf(tonightWithRain, tuesdayPeriod), today.toString(), acc,
        )

        assertEquals(
            "night precip must survive a rejected temperature",
            40, acc.nighttimePrecipProbabilityMap["2026-07-27"],
        )
        assertNotNull("condition must survive", acc.conditionMap["2026-07-27"])
    }

    @Test
    fun `hourly repair recovers the true low over the period window`() {
        val acc = NwsDailyMapper.NwsDayAccumulator()
        NwsDailyMapper.applyForecastPeriods(
            listOf(tonightPeriod, tuesdayPeriod), today.toString(), acc,
        )

        val repairs = NwsDailyMapper.fillTemperatureGapsFromHourly(
            acc.temperatureMap, acc.rejectedTemps, tonightHours,
            lowTempSourceMap = acc.lowTempSourceMap,
        )

        val (high, low) = acc.temperatureMap["2026-07-28"] ?: (null to null)
        // 59, not the calendar-day 58 — the 22:00 reading on the 28th is outside the Tonight window.
        assertEquals("must recover NWS's own overnight low", 59f, low)
        assertEquals("high must be untouched", 78f, high)
        assertEquals(1, repairs.size)
        assertTrue(
            "source must record the repair, got ${acc.lowTempSourceMap["2026-07-28"]}",
            acc.lowTempSourceMap["2026-07-28"]?.startsWith("HOURLY:min") == true,
        )
    }

    @Test
    fun `repair never overwrites a sane value that arrived from another path`() {
        val acc = NwsDailyMapper.NwsDayAccumulator()
        // Grid supplied a good low first; the period path then rejects its sentinel.
        acc.temperatureMap["2026-07-28"] = null to 58f
        NwsDailyMapper.applyForecastPeriods(
            listOf(tonightPeriod, tuesdayPeriod), today.toString(), acc,
        )

        val repairs = NwsDailyMapper.fillTemperatureGapsFromHourly(
            acc.temperatureMap, acc.rejectedTemps, tonightHours,
        )

        assertEquals("existing sane low must win", 58f, acc.temperatureMap["2026-07-28"]?.second)
        assertTrue("nothing to repair", repairs.isEmpty())
    }

    @Test
    fun `sane forecasts are completely unaffected`() {
        val acc = NwsDailyMapper.NwsDayAccumulator()
        val goodTonight = tonightPeriod.copy(temperature = 58)
        NwsDailyMapper.applyForecastPeriods(
            listOf(goodTonight, tuesdayPeriod), today.toString(), acc,
        )

        assertEquals(58f, acc.temperatureMap["2026-07-28"]?.second)
        assertEquals(78f, acc.temperatureMap["2026-07-28"]?.first)
        assertTrue("nothing should be rejected", acc.rejectedTemps.isEmpty())
    }

    @Test
    fun `gridpoint sentinel is dropped rather than merged as the low`() {
        // -73.333degC is exactly -100F; mergeGridpointTemperatures must never see it.
        val rejected = listOf(
            RejectedNwsTemperature(
                origin = "GRID:min",
                dateString = "2026-07-28",
                isMax = false,
                windowStartMs = ZonedDateTime.parse("2026-07-28T03:00:00Z").toInstant().toEpochMilli(),
                windowEndMs = ZonedDateTime.parse("2026-07-28T17:00:00Z").toInstant().toEpochMilli(),
                rawValueF = -100f,
            ),
        )
        val extremes = NwsApi.DailyTemperatureExtremes(
            maxByDate = mapOf("2026-07-28" to 78f),
            minByDate = emptyMap(), // the sentinel was filtered out at parse time
            rejected = rejected,
        )

        val temperatureMap = mutableMapOf<String, Pair<Float?, Float?>>()
        NwsDailyMapper.mergeGridpointTemperatures(temperatureMap, extremes, today)
        assertEquals("high merges normally", 78f, temperatureMap["2026-07-28"]?.first)
        assertNull("no low, awaiting repair", temperatureMap["2026-07-28"]?.second)

        NwsDailyMapper.fillTemperatureGapsFromHourly(temperatureMap, rejected, tonightHours)
        assertEquals("hourly repairs the grid gap", 59f, temperatureMap["2026-07-28"]?.second)
    }

    @Test
    fun `desktop entry point renders the repaired day rather than dropping it`() {
        val extremes = NwsApi.DailyTemperatureExtremes(emptyMap(), emptyMap())
        val daily = NwsDailyMapper.buildDailyForecasts(
            listOf(tonightPeriod, tuesdayPeriod), extremes, today, tonightHours,
        )

        val tomorrow = daily.firstOrNull { it.date == "2026-07-28" }
        assertNotNull("tomorrow must still be present", tomorrow)
        assertEquals(78f, tomorrow!!.highTemp)
        assertEquals("desktop must match Android's repaired low", 59f, tomorrow.lowTemp)
    }

    @Test
    fun `read guard treats an already-stored sentinel as missing`() {
        // The ingest filter only protects new rows. Paths that deliberately read older rows — the
        // desktop's previous-forecast snapshot overlay skips the newest batch by design — would
        // otherwise keep rendering a stored -100, dragging bar geometry off-screen while the label
        // beside it showed a healthy number.
        assertNull((-100f as Float?).orNullIfImplausibleTempF())
        assertNull((Float.NaN as Float?).orNullIfImplausibleTempF())
        assertNull((null as Float?).orNullIfImplausibleTempF())

        assertEquals(58f, (58f as Float?).orNullIfImplausibleTempF())
        assertEquals(78f, (78f as Float?).orNullIfImplausibleTempF())
    }

    @Test
    fun `missing hourly data leaves the slot null rather than fabricating a value`() {
        val acc = NwsDailyMapper.NwsDayAccumulator()
        NwsDailyMapper.applyForecastPeriods(
            listOf(tonightPeriod, tuesdayPeriod), today.toString(), acc,
        )

        val repairs = NwsDailyMapper.fillTemperatureGapsFromHourly(
            acc.temperatureMap, acc.rejectedTemps, emptyList(),
        )

        assertTrue(repairs.isEmpty())
        assertNull("no invented low", acc.temperatureMap["2026-07-28"]?.second)
        assertEquals("high still intact", 78f, acc.temperatureMap["2026-07-28"]?.first)
    }
}
