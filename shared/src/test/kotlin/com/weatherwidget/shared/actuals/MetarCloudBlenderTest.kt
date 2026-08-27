package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class MetarCloudBlenderTest {

    /**
     * The blend is BINLESS (plans/260824-subhourly-metar-cloud-blend.md): points land on the
     * stations' native report timestamps, not on hour marks. `hour` anchors the arithmetic; every
     * expectation key below is a real report time like `hour + 5 * min`.
     */
    private val hour = 1_800_000_000_000L // exactly hour-aligned (divides by 3_600_000)
    private val min = 60_000L

    private fun reading(
        stationId: String,
        timestamp: Long,
        cloudLow: Int?,
        distanceKm: Float,
        api: String = WeatherSource.NWS.id,
        qcFailed: Boolean = false,
        lat: Double = 37.0,
        lon: Double = -122.0,
        isMetar: Boolean = false,
    ) = ObservationReading(
        stationId = stationId,
        stationName = stationId,
        timestamp = timestamp,
        temperature = 70f,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        distanceKm = distanceKm,
        api = api,
        qcFailed = qcFailed,
        cloudCoverLow = cloudLow,
        isMetar = isMetar,
    )

    @Test
    fun `two of five stations report - blend uses only the two and never reaches further`() {
        val readings = listOf(
            // KNUQ/KPAO analogues: real stations reporting sky condition.
            reading("KNUQ", hour + 3 * min, cloudLow = 0, distanceKm = 2f),
            reading("KPAO", hour + 5 * min, cloudLow = 100, distanceKm = 4f),
            // AW020/LOAC1 analogues: PWS stations, no ceilometer, cloudLayers always empty.
            reading("AW020", hour + 3 * min, cloudLow = null, distanceKm = 0.5f),
            reading("LOAC1", hour + 4 * min, cloudLow = null, distanceKm = 1f),
            reading("KSJC", hour + 4 * min, cloudLow = null, distanceKm = 8f),
            // Synthetic rows that must never masquerade as stations: the IDW blend aggregate and
            // the historical-actuals backfill (distanceKm=0 would hijack the near-zero snap).
            reading("NWS_BLEND", hour + 3 * min, cloudLow = 100, distanceKm = 0f),
            reading("NWS_MAIN", hour + 3 * min, cloudLow = 100, distanceKm = 0f),
            // Another API's rows at the same site and hour must not join an NWS blend.
            reading("OPEN_METEO_MAIN", hour + 3 * min, cloudLow = 100, distanceKm = 0f,
                api = WeatherSource.OPEN_METEO.id),
        )

        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)

        // Two carriers -> two points, each IDW-blending both stations (their reports are minutes
        // apart, well inside the 30-minute anchor tolerance). IDW 1/d^2:
        // (0 * 1/4 + 100 * 1/16) / (1/4 + 1/16) = 20.
        assertEquals(
            mapOf((hour + 3 * min) to 20, (hour + 5 * min) to 20),
            result.hours,
        )
        assertEquals(2, result.stats.stationsWithLayers)
        assertEquals(3, result.stats.stationsSkipped)
        assertEquals(2, result.stats.blendWidthByHour[hour + 3 * min])
        assertEquals(2, result.stats.blendWidthByHour[hour + 5 * min])
    }

    @Test
    fun `a single reporting station blends to its own value`() {
        val readings = listOf(
            reading("KPAO", hour + 5 * min, cloudLow = 44, distanceKm = 6f),
            reading("AW020", hour + 5 * min, cloudLow = null, distanceKm = 0.5f),
        )
        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)
        assertEquals(mapOf((hour + 5 * min) to 44), result.hours)
        assertEquals(1, result.stats.blendWidthByHour[hour + 5 * min])
    }

    @Test
    fun `stretches with no reports stay absent - gaps are not interpolated`() {
        val readings = listOf(reading("KNUQ", hour + 3 * min, cloudLow = 0, distanceKm = 2f))
        val result = MetarCloudBlender.blend(readings, hour, hour + 3 * 3_600_000L)
        assertEquals(setOf(hour + 3 * min), result.hours.keys)
        assertFalse(result.hours.containsKey(hour + 3_600_000L))
        assertFalse(result.hours.containsKey(hour + 2 * 3_600_000L))
    }

    @Test
    fun `mixed station cadences yield sub-hourly points with per-point IDW`() {
        // Measured shape at this app's own site (2026-08-24): KNUQ at :15/:35/:55, KSJC at :53.
        // Four points in the hour; stations anchor across offsets while their reports are within
        // 30 minutes of the point.
        val readings = listOf(
            reading("KNUQ", hour + 15 * min, cloudLow = 20, distanceKm = 2f, isMetar = true),
            reading("KNUQ", hour + 35 * min, cloudLow = 44, distanceKm = 2f, isMetar = true),
            reading("KNUQ", hour + 55 * min, cloudLow = 0, distanceKm = 2f, isMetar = true),
            reading("KSJC", hour + 53 * min, cloudLow = 75, distanceKm = 16f, isMetar = true),
        )

        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)

        assertEquals(
            mapOf(
                // KSJC's :53 report is 38 minutes away — past the anchor tolerance, so this point
                // is KNUQ alone.
                (hour + 15 * min) to 20,
                // :35 — KNUQ fresh (44, d=2) + KSJC anchored 18 min (75, d=16):
                // (44 * 1/4 + 75 * 1/256) / (1/4 + 1/256) = 44.
                (hour + 35 * min) to 44,
                // :53 — KSJC fresh (75, d=16) + KNUQ's :55 anchored 2 min (0, d=2):
                // (0 * 1/4 + 75 * 1/256) / (1/4 + 1/256) = 1.
                (hour + 53 * min) to 1,
                // :55 — KNUQ fresh (0, d=2) + KSJC anchored 2 min (75, d=16): -> 1.
                (hour + 55 * min) to 1,
            ),
            result.hours,
        )
        assertEquals(1, result.stats.blendWidthByHour[hour + 15 * min])
        assertEquals(2, result.stats.blendWidthByHour[hour + 35 * min])
        assertEquals(2, result.stats.blendWidthByHour[hour + 53 * min])
        assertEquals(2, result.stats.blendWidthByHour[hour + 55 * min])
    }

    @Test
    fun `a station past the anchor tolerance is absent, one inside it blends`() {
        // Three candidates; KPAO's only carrier sits 29 minutes from the first point (anchors it)
        // and 32 from the last (outside the ±30-minute rule: absent there).
        val readings = listOf(
            reading("KNUQ", hour, cloudLow = 50, distanceKm = 2f),
            reading("KPAO", hour + 29 * min, cloudLow = 100, distanceKm = 4f),
            reading("KNUQ", hour + 61 * min, cloudLow = 50, distanceKm = 2f),
        )

        val result = MetarCloudBlender.blend(readings, hour, hour + 2 * 3_600_000L)

        // :00 — KPAO anchored 29 min: (50 * 1/4 + 100 * 1/16) / (1/4 + 1/16) = 60.
        // :29 — KPAO fresh + KNUQ's :00 anchored 29 min: (50 * 1/4 + 100 * 1/16) / (1/4 + 1/16) = 60.
        // 01:01 — KNUQ fresh; KPAO's :29 report is 32 minutes stale, past tolerance: KNUQ alone.
        assertEquals(
            mapOf(
                hour to 60,
                (hour + 29 * min) to 60,
                (hour + 61 * min) to 50,
            ),
            result.hours,
        )
        assertEquals(2, result.stats.blendWidthByHour[hour])
        assertEquals(1, result.stats.blendWidthByHour[hour + 61 * min])
    }

    @Test
    fun `a station reporting at 47 past emits at its own report time`() {
        // KPAO reports at :47. The binless blend never moves the point to a mark: 13 minutes
        // before the next hour lands a point exactly 13 minutes before the next hour.
        val readings = listOf(reading("KPAO", hour - 13 * min, cloudLow = 44, distanceKm = 6f))
        val result = MetarCloudBlender.blend(readings, hour - 3_600_000L, hour + 3_600_000L)
        assertEquals(mapOf((hour - 13 * min) to 44), result.hours)
    }

    @Test
    fun `sub-hourly reports all emit at their own times`() {
        val readings = listOf(
            reading("KNUQ", hour + 5 * min, cloudLow = 10, distanceKm = 2f),
            reading("KNUQ", hour + 50 * min, cloudLow = 90, distanceKm = 2f),
        )
        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)
        assertEquals(
            mapOf((hour + 5 * min) to 10, (hour + 50 * min) to 90),
            result.hours,
        )
    }

    @Test
    fun `a partial report nearest a point yields to a nearby cloud-carrying report`() {
        // The 06:18 partial METAR (cloud omitted) does not kill the 06:19 point another station
        // made: ~25-30% of official reports omit sky condition, so KNUQ's anchor falls back to
        // the nearest report within tolerance that DID carry one — and because the partial sits
        // nearest that point's timestamp (1 minute vs the carrier's 1 minute; the older first in
        // the total order), the rescue is counted as shadowed.
        val readings = listOf(
            reading("KNUQ", hour + 18 * min, cloudLow = null, distanceKm = 2f),
            reading("KNUQ", hour + 20 * min, cloudLow = 44, distanceKm = 2f),
            reading("KPAO", hour + 19 * min, cloudLow = 100, distanceKm = 4f),
        )
        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)
        // Both points blend both stations: (44 * 1/4 + 100 * 1/16) / (1/4 + 1/16) = 55.
        assertEquals(
            mapOf((hour + 19 * min) to 55, (hour + 20 * min) to 55),
            result.hours,
        )
        assertEquals(1, result.stats.shadowedBuckets)
    }

    @Test
    fun `a stretch where every report omits sky condition emits nothing`() {
        // "Not reported" stays "not reported" — the carrier fallback rescues points where a
        // carrier exists nearby, never invents one.
        val readings = listOf(
            reading("KNUQ", hour + 2 * min, cloudLow = null, distanceKm = 2f),
            reading("KNUQ", hour + 20 * min, cloudLow = null, distanceKm = 2f),
        )
        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)
        assertTrue(result.hours.isEmpty())
        assertEquals(0, result.stats.shadowedBuckets)
    }

    @Test
    fun `carrier fallback stays per-station and still blends the rescued station`() {
        // KNUQ's report nearest the 06:05 point is partial but its 06:20 report carries 0; KPAO
        // carries 100 directly. Both points see both stations, one anchored value each.
        val readings = listOf(
            reading("KNUQ", hour + 2 * min, cloudLow = null, distanceKm = 2f),
            reading("KNUQ", hour + 20 * min, cloudLow = 0, distanceKm = 2f),
            reading("KPAO", hour + 5 * min, cloudLow = 100, distanceKm = 4f),
        )
        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)
        // IDW 1/d^2 at each point: (0 * 1/4 + 100 * 1/16) / (1/4 + 1/16) = 20.
        assertEquals(
            mapOf((hour + 5 * min) to 20, (hour + 20 * min) to 20),
            result.hours,
        )
        assertEquals(2, result.stats.blendWidthByHour[hour + 5 * min])
        assertEquals(2, result.stats.blendWidthByHour[hour + 20 * min])
        // Only the 06:05 point is rescued from the partial; 06:20's own report carries.
        assertEquals(1, result.stats.shadowedBuckets)
    }

    @Test
    fun `the official METAR beats the 5-minute samples at every point it anchors`() {
        // The real shape at KSJC: `/stations/{id}/observations` interleaves the METAR (:53) with
        // ASOS 5-minute rows, one of which lands EXACTLY on the hour mark. Nearest-to-time alone
        // hands the on-the-mark point to the 5-minute row — distance 0 vs 7 minutes — so the
        // station's own 30-minute assessment would lose at its own report's point.
        val readings = listOf(
            reading("KSJC", hour, cloudLow = 0, distanceKm = 16f),                       // 5-min, on the mark
            reading("KSJC", hour - 7 * min, cloudLow = 44, distanceKm = 16f, isMetar = true), // the METAR
        )

        // Window starts 30 minutes early so the :53 METAR (11:53 relative to the 12:00 mark) is
        // inside it — pre-window reports can anchor but never emit points of their own.
        val result = MetarCloudBlender.blend(readings, hour - 30 * min, hour + 3_600_000L)

        // The METAR anchors BOTH points: its own (:53) and the 5-minute row's (:00, 7 minutes
        // away, inside the 30-minute tolerance).
        assertEquals(
            mapOf((hour - 7 * min) to 44, hour to 44),
            result.hours,
        )
        assertEquals(2, result.stats.metarPreferredBuckets)
    }

    @Test
    fun `the freshest METAR wins among several at each point`() {
        // The preference selects a CLASS, not a row. Within the METARs, freshest-to-the-point
        // stands.
        val readings = listOf(
            reading("KSJC", hour - 25 * min, cloudLow = 19, distanceKm = 16f, isMetar = true),
            reading("KSJC", hour - 7 * min, cloudLow = 75, distanceKm = 16f, isMetar = true),
            reading("KSJC", hour, cloudLow = 0, distanceKm = 16f),
        )

        val result = MetarCloudBlender.blend(readings, hour - 30 * min, hour + 3_600_000L)

        assertEquals(
            mapOf(
                (hour - 25 * min) to 19, // its own report, freshest METAR here
                (hour - 7 * min) to 75,  // its own report
                hour to 75,              // the :53 METAR (7 min) outranks the on-the-mark 5-min row
            ),
            result.hours,
        )
    }

    @Test
    fun `a station with no METAR still contributes its 5-minute sample`() {
        // Gaps stay gaps, but a station is never dropped merely for lacking a METAR — and every
        // row written before the isMetar column existed reads false, so this is also the
        // pre-migration path.
        val readings = listOf(reading("KSJC", hour + 2 * min, cloudLow = 100, distanceKm = 16f))

        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)

        assertEquals(mapOf((hour + 2 * min) to 100), result.hours)
        assertEquals(0, result.stats.metarPreferredBuckets)
    }

    @Test
    fun `the METAR preference is per-station, not global`() {
        // One station having a METAR must not suppress another station that only has 5-minute
        // rows; the blend width has to stay 2 at every point.
        val readings = listOf(
            reading("KNUQ", hour - 5 * min, cloudLow = 100, distanceKm = 4f, isMetar = true),
            reading("KNUQ", hour, cloudLow = 0, distanceKm = 4f),
            reading("KSJC", hour, cloudLow = 100, distanceKm = 16f),
        )

        // Window starts 30 minutes early so KNUQ's :55 METAR emits its own point.
        val result = MetarCloudBlender.blend(readings, hour - 30 * min, hour + 3_600_000L)

        assertEquals(
            mapOf((hour - 5 * min) to 100, hour to 100),
            result.hours,
        )
        assertEquals(2, result.stats.blendWidthByHour[hour - 5 * min])
        assertEquals(2, result.stats.blendWidthByHour[hour])
    }

    @Test
    fun `a partial METAR yields to a cloud-carrying 5-minute row rather than blanking the point`() {
        // A METAR that omitted sky condition carries nothing to prefer. The carrier filter runs
        // first, so the station still contributes instead of dropping out of the blend.
        val readings = listOf(
            reading("KSJC", hour - 7 * min, cloudLow = null, distanceKm = 16f, isMetar = true),
            reading("KSJC", hour + 3 * min, cloudLow = 100, distanceKm = 16f),
        )

        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)

        assertEquals(mapOf((hour + 3 * min) to 100), result.hours)
        assertEquals(0, result.stats.metarPreferredBuckets)
    }

    @Test
    fun `qc-failed readings are never blend inputs`() {
        val readings = listOf(
            reading("KNUQ", hour + 3 * min, cloudLow = 0, distanceKm = 2f, qcFailed = true),
            reading("KPAO", hour + 5 * min, cloudLow = 100, distanceKm = 4f),
        )
        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)
        assertEquals(mapOf((hour + 5 * min) to 100), result.hours)
        assertEquals(1, result.stats.blendWidthByHour[hour + 5 * min])
    }

    @Test
    fun `input row order cannot change the blend`() {
        // The trap ActualsRowOrderDeterminismTest exists for: same-timestamp rows resolving
        // differently depending on the caller's query order.
        val base = mutableListOf<ObservationReading>()
        for (h in 0 until 6) {
            val t = hour + h * 3_600_000L
            base += reading("KNUQ", t + 3 * min, cloudLow = h * 10, distanceKm = 2f)
            base += reading("KPAO", t + 47 * min - 3_600_000L, cloudLow = 100 - h * 10, distanceKm = 4f)
            base += reading("AW020", t + 3 * min, cloudLow = null, distanceKm = 0.5f)
            // Same station, same timestamp, different coords: the total order must break the tie.
            base += reading("KNUQ", t + 3 * min, cloudLow = h * 10 + 1, distanceKm = 2f, lat = 37.001)
        }
        val first = MetarCloudBlender.blend(base, hour, hour + 6 * 3_600_000L)
        repeat(5) { seed ->
            val shuffled = java.util.Random(seed.toLong()).let { rnd ->
                base.toMutableList().apply { shuffle(rnd) }
            }
            val again = MetarCloudBlender.blend(shuffled, hour, hour + 6 * 3_600_000L)
            assertEquals("seed=$seed", first.hours, again.hours)
            assertEquals("seed=$seed", first.stats, again.stats)
        }
    }

    @Test
    fun `no real rows yields an empty metar result`() {
        val result = MetarCloudBlender.blend(emptyList(), hour, hour + 3_600_000L)
        assertTrue(result.hours.isEmpty())
        assertTrue(result.isMetarBlend)
        assertEquals(0, result.stats.stationsWithLayers)
        assertEquals(0, result.stats.stationsSkipped)
    }

    @Test
    fun `reports outside the window emit no points but can still anchor inside it`() {
        // The pre-window report (hour-1h+3min) produces no point — points sit on real report
        // times and that one is outside the window. But the in-window point CAN anchor a
        // pre-window report: that is what the padded read exists to hand over. The 11-minute-old
        // KPAO report from just before the window joins the first in-window point's blend.
        val readings = listOf(
            reading("KNUQ", hour - 3_600_000L + 3 * min, cloudLow = 0, distanceKm = 2f),
            reading("KPAO", hour - 11 * min, cloudLow = 80, distanceKm = 4f),
            reading("KNUQ", hour + 3 * min, cloudLow = 100, distanceKm = 2f),
        )
        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)
        // IDW at 00:03: (100 * 1/4 + 80 * 1/16) / (1/4 + 1/16) = 96.
        assertEquals(mapOf((hour + 3 * min) to 96), result.hours)
        assertEquals(2, result.stats.blendWidthByHour[hour + 3 * min])
    }

    /**
     * A reader that behaves like the DAOs do: a raw-timestamp range filter, `start` inclusive and
     * `end` exclusive. It reports the range it was asked for so a test can assert the padding.
     */
    private class FakeSiteReader(private val rows: List<ObservationReading>) {
        var requestedStart: Long? = null
        var requestedEnd: Long? = null

        fun read(start: Long, end: Long): List<ObservationReading> {
            requestedStart = start
            requestedEnd = end
            return rows.filter { it.timestamp in start until end }
        }
    }

    @Test
    fun `the padded read lets a pre-window report anchor the first visible points`() = runBlocking {
        // The Samsung regression's binless form. The 1a-5a cloud graph's actual curve began at 2a
        // because KSJC's 00:30 METAR fell outside a bare `timestamp >= 01:00` read and nothing
        // else reported until 01:53. Points now sit on real report times, so a pre-window report
        // draws no point of its own — but the blend can still ANCHOR it at the first in-window
        // point, which is why the ±30-minute pad survives: it is exactly the anchor tolerance,
        // and dropping the pad would silently de-blend the leading edge. Drive it through a
        // range-filtering reader so shrinking that range back to the bare window fails here.
        val windowStart = hour
        val windowEnd = hour + 4 * 3_600_000L
        val reader = FakeSiteReader(
            listOf(
                // KPAO's 00:47 report is 5 minutes before the window and 8 from the first
                // in-window point: inside the anchor tolerance only because the pad read it.
                reading("KPAO", windowStart - 13 * min, cloudLow = 80, distanceKm = 4f),
                reading("KNUQ", windowStart + 5 * min, cloudLow = 100, distanceKm = 2f),
                reading("KNUQ", windowStart + 65 * min, cloudLow = 100, distanceKm = 2f),
            ),
        )

        val result = MetarCloudBlender.fromSiteRows(
            windowStart, windowEnd, WeatherSource.NWS.id, reader::read,
        )

        // First point (00:05) blends BOTH stations — KPAO aged 18 minutes — IDW 1/d^2:
        // (100 * 1/4 + 80 * 1/16) / (1/4 + 1/16) = 96. The second (01:05) is KNUQ-only: KPAO's
        // report is 78 minutes stale by then, past the 30-minute anchor tolerance.
        assertEquals(
            mapOf(
                (windowStart + 5 * min) to 96,
                (windowStart + 65 * min) to 100,
            ),
            result.hours,
        )
        // The pad is the anchor tolerance, no wider: a full hour would drag whole extra hour
        // marks — and the synthetic rows sitting on them — into the read.
        assertEquals(windowStart - 30 * min, reader.requestedStart)
        assertEquals(windowEnd + 30 * min, reader.requestedEnd)
    }

    @Test
    fun `fromSiteRows bounds the synthetic series to the window even when the read was padded`() = runBlocking {
        // The non-NWS branch reads synthetic rows that sit ON hour marks, so it needs none of the
        // rounding tolerance the padded read grants the NWS branch. A caller whose endMs is
        // mid-hour must not gain an actual for the hour after it.
        val readings = listOf(
            reading("WEATHER_API_MAIN", hour - 3_600_000L, cloudLow = 10, distanceKm = 0f,
                api = WeatherSource.WEATHER_API.id),
            reading("WEATHER_API_MAIN", hour, cloudLow = 30, distanceKm = 0f,
                api = WeatherSource.WEATHER_API.id),
            reading("WEATHER_API_MAIN", hour + 3_600_000L, cloudLow = 60, distanceKm = 0f,
                api = WeatherSource.WEATHER_API.id),
        )

        val result = MetarCloudBlender.fromSiteRows(
            hour, hour + 45 * min, WeatherSource.WEATHER_API.id, FakeSiteReader(readings)::read,
        )

        assertEquals(mapOf(hour to 30), result.hours)
    }

    @Test
    fun `fromSiteRows delegates NWS to the station blend`() = runBlocking {
        val readings = listOf(
            reading("KNUQ", hour + 3 * min, cloudLow = 40, distanceKm = 2f),
            reading("KPAO", hour + 5 * min, cloudLow = 80, distanceKm = 4f),
            // Synthetic rows must not masquerade as stations, exactly as in blend().
            reading("NWS_BLEND", hour + 3 * min, cloudLow = 100, distanceKm = 0f),
            reading("NWS_MAIN", hour + 3 * min, cloudLow = 100, distanceKm = 0f),
        )

        val result = MetarCloudBlender.fromSiteRows(
            hour, hour + 3_600_000L, WeatherSource.NWS.id, FakeSiteReader(readings)::read,
        )

        // (40 * 1/4 + 80 * 1/16) / (1/4 + 1/16) = 48 — the blend, not a synthetic pin — at each
        // report's own time (the two reports are 2 minutes apart, so both points blend both
        // stations).
        assertEquals(
            mapOf((hour + 3 * min) to 48, (hour + 5 * min) to 48),
            result.hours,
        )
        assertTrue(result.isMetarBlend)
    }

    @Test
    fun `NWS series uses METAR transport rows to fill NWS transport holes`() = runBlocking {
        val readings = listOf(
            reading("KNUQ", hour, cloudLow = 100, distanceKm = 2f),
            reading("KNUQ", hour + 15 * min, cloudLow = 100, distanceKm = 2f),
            // The live Samsung shape had no NWS carrier for 80 minutes, even though the same
            // airport's independently stored Aviation Weather reports covered the interval.
            reading(
                "KNUQ",
                hour + 35 * min,
                cloudLow = 75,
                distanceKm = 2f,
                api = WeatherSource.METAR.id,
                isMetar = true,
            ),
            reading(
                "KNUQ",
                hour + 55 * min,
                cloudLow = 75,
                distanceKm = 2f,
                api = WeatherSource.METAR.id,
                isMetar = true,
            ),
            reading(
                "KNUQ",
                hour + 75 * min,
                cloudLow = 75,
                distanceKm = 2f,
                api = WeatherSource.METAR.id,
                isMetar = true,
            ),
            reading("KNUQ", hour + 95 * min, cloudLow = 75, distanceKm = 2f),
            // Unrelated measured/model provenance remains excluded.
            reading(
                "OTHER",
                hour + 45 * min,
                cloudLow = 0,
                distanceKm = 0f,
                api = WeatherSource.TOMORROW_IO.id,
            ),
        )

        val result = MetarCloudBlender.fromSiteRows(
            hour,
            hour + 2 * 3_600_000L,
            WeatherSource.NWS.id,
            FakeSiteReader(readings)::read,
        )

        assertEquals(
            listOf(0L, 15L, 35L, 55L, 75L, 95L).map { hour + it * min },
            result.hours.keys.toList(),
        )
        assertFalse(result.hours.containsKey(hour + 45 * min))
    }

    @Test
    fun `NWS and METAR copies at one station timestamp deduplicate deterministically`() = runBlocking {
        val sharedTimestamp = hour + 15 * min
        val readings = listOf(
            reading(
                "KNUQ",
                sharedTimestamp,
                cloudLow = 10,
                distanceKm = 2.1f,
                api = WeatherSource.METAR.id,
                isMetar = true,
            ),
            reading("KNUQ", sharedTimestamp, cloudLow = 75, distanceKm = 2f, isMetar = true),
            // A primary partial row must not suppress the supplemental carrier at a later report.
            reading("KNUQ", hour + 35 * min, cloudLow = null, distanceKm = 2f),
            reading(
                "KNUQ",
                hour + 35 * min,
                cloudLow = 44,
                distanceKm = 2.1f,
                api = WeatherSource.METAR.id,
                isMetar = true,
            ),
        )

        val forward = MetarCloudBlender.fromSiteRows(
            hour,
            hour + 3_600_000L,
            WeatherSource.NWS.id,
            FakeSiteReader(readings)::read,
        )
        val reversed = MetarCloudBlender.fromSiteRows(
            hour,
            hour + 3_600_000L,
            WeatherSource.NWS.id,
            FakeSiteReader(readings.reversed())::read,
        )

        assertEquals(mapOf(sharedTimestamp to 75, (hour + 35 * min) to 44), forward.hours)
        assertEquals(forward, reversed)
        assertEquals(1, forward.stats.blendWidthByHour[sharedTimestamp])
        assertEquals(1, forward.stats.blendWidthByHour[hour + 35 * min])
    }

    @Test
    fun `fromSiteRows pins non-NWS sources to their synthetic backfill row and reports the total`() = runBlocking {
        val readings = listOf(
            // Total wins over the low layer (reversed 2026-08-27; see VisibleCloudCover).
            reading("WEATHER_API_MAIN", hour, cloudLow = 30, distanceKm = 0f,
                api = WeatherSource.WEATHER_API.id)
                .copy(cloudCover = 70),
            // A row with no total falls back to what its bands report.
            reading("WEATHER_API_MAIN", hour + 3_600_000L, cloudLow = 55, distanceKm = 0f,
                api = WeatherSource.WEATHER_API.id),
            // A real station's row (or another source's synthetic row) must never join the series.
            reading("KNUQ", hour, cloudLow = 90, distanceKm = 2f),
            reading("NWS_MAIN", hour, cloudLow = 90, distanceKm = 0f),
        )

        val result = MetarCloudBlender.fromSiteRows(
            hour, hour + 2 * 3_600_000L, WeatherSource.WEATHER_API.id, FakeSiteReader(readings)::read,
        )

        assertEquals(
            mapOf(hour to 70, (hour + 3_600_000L) to 55),
            result.hours,
        )
        assertFalse(result.isMetarBlend)
    }

    @Test
    fun `fromSiteRows rejects rows carrying another api even when station id matches`() = runBlocking {
        val readings = listOf(
            reading(
                "WEATHER_API_MAIN",
                hour,
                cloudLow = 56,
                distanceKm = 0f,
                api = WeatherSource.WEATHER_API.id,
            ),
            reading(
                "WEATHER_API_MAIN",
                hour + 15 * min,
                cloudLow = 100,
                distanceKm = 0f,
                api = WeatherSource.NWS.id,
            ),
        )

        val result = MetarCloudBlender.fromSiteRows(
            hour,
            hour + 3_600_000L,
            WeatherSource.WEATHER_API.id,
            FakeSiteReader(readings)::read,
        )

        assertEquals(mapOf(hour to 56), result.hours)
        assertFalse(result.isMetarBlend)
    }

    @Test
    fun `fromSiteRows rejects cached silurian forecast rows`() = runBlocking {
        val result = MetarCloudBlender.fromSiteRows(
            hour,
            hour + 3_600_000L,
            WeatherSource.SILURIAN.id,
        ) { _, _ ->
            listOf(
                reading(
                    "SILURIAN_MAIN",
                    hour,
                    cloudLow = null,
                    distanceKm = 0f,
                    api = WeatherSource.SILURIAN.id,
                ).copy(cloudCover = 100),
            )
        }

        // The claim that matters is unchanged: Silurian's include_past payload is forecast output,
        // and it must never surface as a measured cloud curve. It is now excluded by never being an
        // eligible PROVIDER rather than by the source being refused outright.
        assertTrue(result.hours.isEmpty())
    }

    /**
     * SUPERSEDED 2026-08-24. This used to also assert `readCalled == false` — the gate fired before
     * touching storage, which was a free optimisation while a forecast-only source could not have
     * cloud from anywhere. It now borrows a measured feed, so it MUST read storage to look for that
     * feed's rows; refusing to read is refusing to have a curve. The row-level exclusion above is
     * what keeps its own forecast output out.
     */
    @Test
    fun `silurian reads storage now that it borrows a measured cloud feed`() = runBlocking {
        var readCalled = false
        MetarCloudBlender.fromSiteRows(hour, hour + 3_600_000L, WeatherSource.SILURIAN.id) { _, _ ->
            readCalled = true
            emptyList()
        }
        assertTrue("a borrowing source must look for its provider's rows", readCalled)
    }

    @Test
    fun `Tomorrow cloud actuals prefer realtime then fall back to recent history`() = runBlocking {
        val readings = listOf(
            reading(
                "TOMORROW_IO_MAIN",
                hour + 2 * min,
                cloudLow = 100,
                distanceKm = 0f,
                api = WeatherSource.TOMORROW_IO.id,
            ),
            reading(
                "TOMORROW_IO_RECENT_HISTORY",
                hour + 3 * min,
                cloudLow = 88,
                distanceKm = 0f,
                api = WeatherSource.TOMORROW_IO.id,
            ),
            reading(
                "TOMORROW_IO_REALTIME",
                hour + 8 * min,
                cloudLow = 56,
                distanceKm = 0f,
                api = WeatherSource.TOMORROW_IO.id,
            ),
            reading(
                "TOMORROW_IO_RECENT_HISTORY",
                hour + 50 * min,
                cloudLow = 72,
                distanceKm = 0f,
                api = WeatherSource.TOMORROW_IO.id,
            ),
        )

        val result = MetarCloudBlender.fromSiteRows(
            hour,
            hour + 2 * 3_600_000L,
            WeatherSource.TOMORROW_IO.id,
            FakeSiteReader(readings)::read,
        )

        assertEquals(mapOf(hour to 56, hour + 3_600_000L to 72), result.hours)
        assertFalse(result.isMetarBlend)
    }

    @Test
    fun `blend captures nearest station as dominant contribution with raw cloud percent`() {
        val readings = listOf(
            reading("KNUQ", hour + 15 * min, cloudLow = 44, distanceKm = 3.5f),
            reading("KSJC", hour + 15 * min, cloudLow = 80, distanceKm = 12.0f),
        )

        val result = MetarCloudBlender.blend(
            readings = readings,
            startMs = hour,
            endMs = hour + 3_600_000L,
            providerApi = WeatherSource.NWS.id,
        )

        val dominant = result.dominantContribution
        org.junit.Assert.assertNotNull(dominant)
        assertEquals("KNUQ", dominant!!.stationId)
        assertEquals(44f, dominant.rawTemp)
        assertEquals(hour + 15 * min, dominant.lastReadingMs)
        assertFalse(dominant.isSynthetic)
    }
}
