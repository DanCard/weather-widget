package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pins the 2026-08-03 field report: "observed temp on hourly graph doesn't seem to match station list
 * temperatures" (Samsung, emulator and desktop alike).
 *
 * Real rows from the desktop DB at 08:20 local. Every station within reach had last reported between
 * 0 and 70 minutes earlier, and the NWS forecast was ramping 63°→69° across that hour, so each stale
 * station entered the blend carried forward by the forecast's rise — KPAO by +2.65°F. With the default
 * 95% personal-station discount, AW020 (nearest, and the ONLY station with a reading at the target
 * minute) held under 10% of the weight, so ~90% of the "observed" dot was forecast extrapolation and
 * it landed at 65.39° — above the 65.0° maximum of every real reading in the stations list.
 *
 * This test does not assert that behaviour is *desirable*; it fixes the arithmetic in place so the
 * Blend tab's table is provably the same computation that moves the graph.
 */
@Category(ShortDuration::class)
class BlendBreakdownCaptureTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val lat = 37.417
    private val lon = -122.089

    private fun ms(local: String): Long =
        LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun observation(
        stationId: String,
        time: String,
        temp: Float,
        distanceKm: Float,
        stationType: String,
    ) = ObservationReading(
        stationId = stationId,
        stationName = stationId,
        timestamp = ms(time),
        temperature = temp,
        condition = "observed",
        locationLat = lat,
        locationLon = lon,
        distanceKm = distanceKm,
        stationType = stationType,
        api = WeatherSource.NWS.id,
        fetchedAt = ms(time),
    )

    /** NWS hourly forecast for the site that morning — this is what the extrapolation rides on. */
    private fun forecasts() = listOf(
        "2026-08-03T06:00:00" to 60f,
        "2026-08-03T07:00:00" to 60f,
        "2026-08-03T08:00:00" to 63f,
        "2026-08-03T09:00:00" to 69f,
        "2026-08-03T10:00:00" to 73f,
    ).map { (time, temp) ->
        HourlyForecast(
            dateTime = ms(time),
            temperature = temp,
            condition = "Sunny",
            source = WeatherSource.NWS.id,
            locationLat = lat,
            locationLon = lon,
        )
    }

    // Each station's readings up to 08:20, exactly as the desktop DB held them.
    private fun observations() = listOf(
        observation("AW020", "2026-08-03T08:00:00", 63.0f, 2.22f, "PERSONAL"),
        observation("AW020", "2026-08-03T08:10:00", 64.0f, 2.22f, "PERSONAL"),
        observation("AW020", "2026-08-03T08:20:00", 65.0f, 2.22f, "PERSONAL"),
        observation("KNUQ", "2026-08-03T07:45:00", 64.4f, 3.82f, "OFFICIAL"),
        observation("KNUQ", "2026-08-03T08:15:00", 64.4f, 3.82f, "OFFICIAL"),
        observation("KPAO", "2026-08-03T07:47:00", 64.4f, 6.06f, "OFFICIAL"),
        observation("KSJC", "2026-08-03T08:05:00", 64.4f, 15.91f, "OFFICIAL"),
        observation("LOAC1", "2026-08-03T07:10:00", 55.0f, 8.33f, "PERSONAL"),
    )

    private fun blend(captureBreakdowns: Int) = ActualTemperatureSeriesBuilder.blendObservationSeries(
        observations = observations(),
        hourlyForecasts = forecasts(),
        displaySourceId = WeatherSource.NWS.id,
        userLat = lat,
        userLon = lon,
        startMs = ms("2026-08-03T00:00:00"),
        endMs = ms("2026-08-03T23:59:00"),
        personalStationWeight = 0.05, // DEFAULT_PERSONAL_STATION_DISCOUNT = 95
        zoneId = zone,
        captureBreakdowns = captureBreakdowns,
    )

    private fun breakdownAt0820() =
        blend(captureBreakdowns = 100).breakdowns.single { it.targetMs == ms("2026-08-03T08:20:00") }

    @Test
    fun `contribution table matches the values fed to the blend`() {
        val byStation = breakdownAt0820().contributions.associateBy { it.stationId }
        assertEquals(setOf("AW020", "KNUQ", "KPAO", "KSJC", "LOAC1"), byStation.keys)

        // station to (raw, resolved, kind, weight share)
        val expected = mapOf(
            "AW020" to Quad(65.0f, 65.00f, "observed", 0.0984),
            "KNUQ" to Quad(64.4f, 64.90f, "forecast_extrapolated", 0.6464),
            "KPAO" to Quad(64.4f, 67.05f, "forecast_extrapolated", 0.2158),
            "KSJC" to Quad(64.4f, 65.90f, "forecast_extrapolated", 0.0351),
            "LOAC1" to Quad(55.0f, 59.50f, "forecast_extrapolated", 0.0043),
        )

        expected.forEach { (stationId, want) ->
            val got = byStation.getValue(stationId)
            assertEquals("$stationId raw", want.raw, got.rawTemp, 0.01f)
            assertEquals("$stationId resolved", want.resolved, got.resolvedTemp, 0.01f)
            assertEquals("$stationId kind", want.kind, got.sourceKind)
            assertEquals("$stationId share", want.share, got.weightShare, 0.001)
        }

        assertEquals(1.0, byStation.values.sumOf { it.weightShare }, 0.0001)
    }

    @Test
    fun `age column reports staleness at the blended timestamp`() {
        val byStation = breakdownAt0820().contributions.associateBy { it.stationId }
        // KPAO is 33 minutes behind and still holds ~22% of the weight — the whole reason the column
        // exists. Ages come from the anchor reading, not from "now".
        assertEquals(0L, byStation.getValue("AW020").ageMs / 60_000L)
        assertEquals(5L, byStation.getValue("KNUQ").ageMs / 60_000L)
        assertEquals(33L, byStation.getValue("KPAO").ageMs / 60_000L)
        assertEquals(15L, byStation.getValue("KSJC").ageMs / 60_000L)
        assertEquals(70L, byStation.getValue("LOAC1").ageMs / 60_000L)
    }

    /** The stale official stations outweigh the one station that actually measured anything. */
    @Test
    fun `extrapolated contributions dominate the weight`() {
        val contributions = breakdownAt0820().contributions
        val extrapolatedShare = contributions
            .filter { it.sourceKind == "forecast_extrapolated" }
            .sumOf { it.weightShare }
        assertTrue("extrapolated share was $extrapolatedShare", extrapolatedShare > 0.90)
    }

    @Test
    fun `capture is observationally inert`() {
        val without = blend(captureBreakdowns = 0)
        val with = blend(captureBreakdowns = 100)

        assertEquals(without.observations, with.observations)
        assertEquals(without.stats, with.stats)
        assertTrue("capture off must not retain breakdowns", without.breakdowns.isEmpty())
    }

    @Test
    fun `capture keeps only the most recent N, newest first`() {
        val capped = blend(captureBreakdowns = 2).breakdowns
        val all = blend(captureBreakdowns = 100).breakdowns

        assertEquals(2, capped.size)
        assertEquals(all.take(2).map { it.targetMs }, capped.map { it.targetMs })
        // Newest first, so the graph's live dot is the first row in the tab.
        assertEquals(all.map { it.targetMs }.sortedDescending(), all.map { it.targetMs })
    }

    @Test
    fun `formatter renders the agreed columns`() {
        val table = BlendTableFormatter.format(breakdownAt0820(), useCelsius = false, zoneId = zone)

        assertEquals("08:20", table.timeLabel)
        assertEquals("65.39°", table.blendedLabel)
        assertEquals(5, table.stationCount)

        // Nearest first, matching the Observations tab's ordering.
        assertEquals(
            listOf("AW020", "KNUQ", "KPAO", "LOAC1", "KSJC"),
            table.rows.map { it.station },
        )

        // Single-letter codes, explained by LEGEND: P = personal, R = real reading.
        val aw020 = table.rows.first()
        assertEquals("AW020", aw020.station)
        assertEquals("P", aw020.type)
        assertEquals("2.22", aw020.km)
        assertEquals("08:20", aw020.lastRead)
        assertEquals("0m", aw020.age)
        assertEquals("65.0", aw020.raw)
        assertEquals("65.00 R", aw020.valueFedToBlend)
        assertEquals("9.8%", aw020.weightShare)
        assertTrue(!aw020.isExtrapolated)

        // The station that actually drives the result is neither the nearest nor the freshest.
        val knuq = table.rows.single { it.station == "KNUQ" }
        assertEquals("O", knuq.type)
        assertEquals("5m", knuq.age)
        assertEquals("64.4", knuq.raw)
        assertEquals("64.90 E", knuq.valueFedToBlend)
        assertEquals("64.6%", knuq.weightShare)
        assertTrue(knuq.isExtrapolated)
    }

    @Test
    fun `text renderer aligns columns`() {
        val text = BlendTableFormatter.renderText(
            BlendTableFormatter.format(listOf(breakdownAt0820()), useCelsius = false, zoneId = zone),
        )
        val lines = text.lines()

        assertTrue(lines[0], lines[0].startsWith("08:20  ->  65.39\u00B0   5 stations"))
        assertEquals(BlendTableFormatter.COLUMN_HEADERS[0], lines[1].substringBefore("  "))

        // Station ids start their line — the Android tab relies on this to place its link spans.
        val dataLines = lines.drop(3).takeWhile { it.isNotBlank() }
        assertEquals(5, dataLines.size)
        assertEquals(listOf("AW020", "KNUQ", "KPAO", "LOAC1", "KSJC"), dataLines.map { it.substringBefore("  ") })
        assertEquals(8, BlendTableFormatter.COLUMN_HEADERS.size)

        // Fixed-width: every data line pads to the same column layout.
        val shareColumn = dataLines.map { it.indexOf("%") }
        assertEquals("share column should align: $shareColumn", 1, shareColumn.distinct().size)
    }

    @Test
    fun `text renderer appends the legend explaining the letter codes`() {
        val text = BlendTableFormatter.renderText(
            BlendTableFormatter.format(listOf(breakdownAt0820()), useCelsius = false, zoneId = zone),
        )
        BlendTableFormatter.LEGEND.forEach { assertTrue("missing legend line: $it", text.contains(it)) }
    }

    /**
     * The current-point-only tab can never show an interpolated contribution: `interpolated` needs a
     * reading on BOTH sides of the target time, and every observation timestamp is itself a candidate,
     * so a later reading would be the newest point instead. Keying a code that never appears is noise.
     */
    @Test
    fun `legend omits the unreachable interpolated code`() {
        val legend = BlendTableFormatter.LEGEND.joinToString(" ")
        assertTrue("legend should key R", legend.contains("R = real reading"))
        assertTrue("legend should key E", legend.contains("E = extrapolated"))
        assertTrue("legend should not key I: $legend", !legend.contains("I ="))

        val kinds = breakdownAt0820().contributions.map { it.sourceKind }.toSet()
        assertTrue("newest point had an interpolated contributor: $kinds", "interpolated" !in kinds)
    }

    private data class Quad(val raw: Float, val resolved: Float, val kind: String, val share: Double)
}
