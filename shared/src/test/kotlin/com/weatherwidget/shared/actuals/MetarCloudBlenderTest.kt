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

        // IDW with 1/d^2 weights: (0 * 1/4 + 100 * 1/16) / (1/4 + 1/16) = 20.
        assertEquals(mapOf(hour to 20), result.hours)
        assertEquals(2, result.stats.stationsWithLayers)
        assertEquals(3, result.stats.stationsSkipped)
        assertEquals(2, result.stats.blendWidthByHour[hour])
    }

    @Test
    fun `a single reporting station blends to its own value`() {
        val readings = listOf(
            reading("KPAO", hour + 5 * min, cloudLow = 44, distanceKm = 6f),
            reading("AW020", hour + 5 * min, cloudLow = null, distanceKm = 0.5f),
        )
        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)
        assertEquals(mapOf(hour to 44), result.hours)
        assertEquals(1, result.stats.blendWidthByHour[hour])
    }

    @Test
    fun `an hour with no contributor stays absent - gaps are not interpolated`() {
        val readings = listOf(reading("KNUQ", hour + 3 * min, cloudLow = 0, distanceKm = 2f))
        val result = MetarCloudBlender.blend(readings, hour, hour + 3 * 3_600_000L)
        assertEquals(setOf(hour), result.hours.keys)
        assertFalse(result.hours.containsKey(hour + 3_600_000L))
        assertFalse(result.hours.containsKey(hour + 2 * 3_600_000L))
    }

    @Test
    fun `a station reporting at 47 past rounds into the following hour`() {
        // KPAO reports at :47. Flooring to the hour would drop it almost entirely (measured: 29
        // hours become 1); round-to-nearest is the physically correct rule for an instantaneous
        // reading. 13 minutes before the next hour mark -> that hour.
        val readings = listOf(reading("KPAO", hour - 13 * min, cloudLow = 44, distanceKm = 6f))
        val result = MetarCloudBlender.blend(readings, hour - 3_600_000L, hour + 3_600_000L)
        assertEquals(mapOf(hour to 44), result.hours)
    }

    @Test
    fun `each station contributes one value per hour - the reading nearest the hour mark`() {
        val readings = listOf(
            reading("KNUQ", hour + 5 * min, cloudLow = 10, distanceKm = 2f),
            reading("KNUQ", hour + 50 * min, cloudLow = 90, distanceKm = 2f),
        )
        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)
        assertEquals(mapOf(hour to 10), result.hours)
    }

    @Test
    fun `a partial report nearest the hour yields to a cloud-carrying report in the same bucket`() {
        // The 06:00-hour bucket held a 06:00 partial METAR (cloud omitted) and a 06:20 full report:
        // nearest-to-the-hour alone would drop the station's hour even though the station DID
        // report sky condition. ~25-30% of official reports omit cloudLayers, so this is the normal
        // case, not an edge — fall back to the nearest carrier inside the same ±30-minute bucket.
        val readings = listOf(
            reading("KNUQ", hour + 2 * min, cloudLow = null, distanceKm = 2f),
            reading("KNUQ", hour + 20 * min, cloudLow = 44, distanceKm = 2f),
        )
        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)
        assertEquals(mapOf(hour to 44), result.hours)
        assertEquals(1, result.stats.shadowedBuckets)
    }

    @Test
    fun `a bucket where every report omits sky condition emits nothing`() {
        // "Not reported" stays "not reported" — the preference rescues hours where a carrier
        // exists, never invents one.
        val readings = listOf(
            reading("KNUQ", hour + 2 * min, cloudLow = null, distanceKm = 2f),
            reading("KNUQ", hour + 20 * min, cloudLow = null, distanceKm = 2f),
        )
        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)
        assertTrue(result.hours.isEmpty())
        assertEquals(0, result.stats.shadowedBuckets)
    }

    @Test
    fun `carrier preference stays per-station and still blends the rescued station`() {
        // KNUQ's nearest report is partial but its 06:20 report carries 0; KPAO carries 100
        // directly. The blend sees both stations, exactly one value each.
        val readings = listOf(
            reading("KNUQ", hour + 2 * min, cloudLow = null, distanceKm = 2f),
            reading("KNUQ", hour + 20 * min, cloudLow = 0, distanceKm = 2f),
            reading("KPAO", hour + 5 * min, cloudLow = 100, distanceKm = 4f),
        )
        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)
        // IDW 1/d^2: (0 * 1/4 + 100 * 1/16) / (1/4 + 1/16) = 20.
        assertEquals(mapOf(hour to 20), result.hours)
        assertEquals(2, result.stats.blendWidthByHour[hour])
        assertEquals(1, result.stats.shadowedBuckets)
    }

    @Test
    fun `the official METAR beats a 5-minute sample sitting exactly on the hour mark`() {
        // The real shape at KSJC: `/stations/{id}/observations` interleaves the METAR (:53) with
        // ASOS 5-minute rows, one of which lands EXACTLY on the hour mark. Nearest-to-mark alone
        // hands the hour to the 5-minute row every time — distance 0 vs 7 minutes — so the
        // station's own 30-minute assessment could never be selected.
        val readings = listOf(
            reading("KSJC", hour, cloudLow = 0, distanceKm = 16f),                       // 5-min, on the mark
            reading("KSJC", hour - 7 * min, cloudLow = 44, distanceKm = 16f, isMetar = true), // the METAR
        )

        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)

        assertEquals(mapOf(hour to 44), result.hours)
        assertEquals(1, result.stats.metarPreferredBuckets)
    }

    @Test
    fun `nearest-to-the-hour still decides among several METARs`() {
        // The preference selects a CLASS, not a row. Within the METARs, the existing rule stands.
        val readings = listOf(
            reading("KSJC", hour - 25 * min, cloudLow = 19, distanceKm = 16f, isMetar = true),
            reading("KSJC", hour - 7 * min, cloudLow = 75, distanceKm = 16f, isMetar = true),
            reading("KSJC", hour, cloudLow = 0, distanceKm = 16f),
        )

        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)

        assertEquals(mapOf(hour to 75), result.hours)
    }

    @Test
    fun `a station with no METAR in the bucket still contributes its 5-minute sample`() {
        // Gaps stay gaps, but a station is never dropped merely for lacking a METAR this hour —
        // and every row written before the isMetar column existed reads false, so this is also the
        // pre-migration path.
        val readings = listOf(reading("KSJC", hour + 2 * min, cloudLow = 100, distanceKm = 16f))

        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)

        assertEquals(mapOf(hour to 100), result.hours)
        assertEquals(0, result.stats.metarPreferredBuckets)
    }

    @Test
    fun `the METAR preference is per-station, not global`() {
        // One station having a METAR must not suppress another station that only has 5-minute rows;
        // the blend width has to stay 2.
        val readings = listOf(
            reading("KNUQ", hour - 5 * min, cloudLow = 100, distanceKm = 4f, isMetar = true),
            reading("KNUQ", hour, cloudLow = 0, distanceKm = 4f),
            reading("KSJC", hour, cloudLow = 100, distanceKm = 16f),
        )

        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)

        assertEquals(mapOf(hour to 100), result.hours)
        assertEquals(2, result.stats.blendWidthByHour[hour])
    }

    @Test
    fun `a partial METAR yields to a cloud-carrying 5-minute row rather than blanking the hour`() {
        // A METAR that omitted sky condition carries nothing to prefer. The carrier filter runs
        // first, so the station still contributes instead of dropping out of the blend.
        val readings = listOf(
            reading("KSJC", hour - 7 * min, cloudLow = null, distanceKm = 16f, isMetar = true),
            reading("KSJC", hour + 3 * min, cloudLow = 100, distanceKm = 16f),
        )

        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)

        assertEquals(mapOf(hour to 100), result.hours)
        assertEquals(0, result.stats.metarPreferredBuckets)
    }

    @Test
    fun `qc-failed readings are never blend inputs`() {
        val readings = listOf(
            reading("KNUQ", hour + 3 * min, cloudLow = 0, distanceKm = 2f, qcFailed = true),
            reading("KPAO", hour + 5 * min, cloudLow = 100, distanceKm = 4f),
        )
        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)
        assertEquals(mapOf(hour to 100), result.hours)
        assertEquals(1, result.stats.blendWidthByHour[hour])
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
    fun `readings outside the requested window are dropped`() {
        val readings = listOf(
            reading("KNUQ", hour - 3_600_000L + 3 * min, cloudLow = 0, distanceKm = 2f),
            reading("KNUQ", hour + 3 * min, cloudLow = 100, distanceKm = 2f),
        )
        val result = MetarCloudBlender.blend(readings, hour, hour + 3_600_000L)
        assertEquals(mapOf(hour to 100), result.hours)
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
    fun `a report in the half-hour before the window still fills the first visible hour`() = runBlocking {
        // The Samsung regression, end to end. The 1a-5a cloud graph's actual curve began at 2a
        // because KSJC's 00:30 METAR — which rounds INTO 01:00, the first visible hour — fell
        // outside a bare `timestamp >= 01:00` read. The blend was always willing to bucket it; the
        // READ had to hand it over, which is why fromSiteRows now owns the range. Drive it through
        // a range-filtering reader so shrinking that range back to the bare window fails here.
        val windowStart = hour
        val windowEnd = hour + 4 * 3_600_000L
        val reader = FakeSiteReader(
            listOf(
                reading("KSJC", windowStart - 30 * min, cloudLow = 75, distanceKm = 16f),
                reading("KSJC", windowStart + 65 * min, cloudLow = 100, distanceKm = 16f),
                reading("KSJC", windowStart + 130 * min, cloudLow = 100, distanceKm = 16f),
            ),
        )

        val result = MetarCloudBlender.fromSiteRows(
            windowStart, windowEnd, WeatherSource.NWS.id, reader::read,
        )

        assertEquals(
            mapOf(
                windowStart to 75,
                (windowStart + 3_600_000L) to 100,
                (windowStart + 2 * 3_600_000L) to 100,
            ),
            result.hours,
        )
        // The pad is the bucketing tolerance, no wider: a full hour would drag whole extra hour
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
            reading("OPEN_METEO_MAIN", hour - 3_600_000L, cloudLow = 10, distanceKm = 0f,
                api = WeatherSource.OPEN_METEO.id),
            reading("OPEN_METEO_MAIN", hour, cloudLow = 30, distanceKm = 0f,
                api = WeatherSource.OPEN_METEO.id),
            reading("OPEN_METEO_MAIN", hour + 3_600_000L, cloudLow = 60, distanceKm = 0f,
                api = WeatherSource.OPEN_METEO.id),
        )

        val result = MetarCloudBlender.fromSiteRows(
            hour, hour + 45 * min, WeatherSource.OPEN_METEO.id, FakeSiteReader(readings)::read,
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

        // (40 * 1/4 + 80 * 1/16) / (1/4 + 1/16) = 48 — the blend, not a synthetic pin.
        assertEquals(mapOf(hour to 48), result.hours)
        assertTrue(result.isMetarBlend)
    }

    @Test
    fun `fromSiteRows pins non-NWS sources to their synthetic backfill row and prefers the low layer`() = runBlocking {
        val readings = listOf(
            reading("OPEN_METEO_MAIN", hour, cloudLow = 30, distanceKm = 0f,
                api = WeatherSource.OPEN_METEO.id),
            // A row whose low is missing falls back to the total column.
            reading("OPEN_METEO_MAIN", hour + 3_600_000L, cloudLow = null, distanceKm = 0f,
                api = WeatherSource.OPEN_METEO.id)
                .copy(cloudCover = 55),
            // A real station's row (or another source's synthetic row) must never join the series.
            reading("KNUQ", hour, cloudLow = 90, distanceKm = 2f),
            reading("NWS_MAIN", hour, cloudLow = 90, distanceKm = 0f),
        )

        val result = MetarCloudBlender.fromSiteRows(
            hour, hour + 2 * 3_600_000L, WeatherSource.OPEN_METEO.id, FakeSiteReader(readings)::read,
        )

        assertEquals(
            mapOf(hour to 30, (hour + 3_600_000L) to 55),
            result.hours,
        )
        assertFalse(result.isMetarBlend)
    }

    @Test
    fun `fromSiteRows rejects rows carrying another api even when station id matches`() = runBlocking {
        val readings = listOf(
            reading(
                "OPEN_METEO_MAIN",
                hour,
                cloudLow = 56,
                distanceKm = 0f,
                api = WeatherSource.OPEN_METEO.id,
            ),
            reading(
                "OPEN_METEO_MAIN",
                hour + 15 * min,
                cloudLow = 100,
                distanceKm = 0f,
                api = WeatherSource.NWS.id,
            ),
        )

        val result = MetarCloudBlender.fromSiteRows(
            hour,
            hour + 3_600_000L,
            WeatherSource.OPEN_METEO.id,
            FakeSiteReader(readings)::read,
        )

        assertEquals(mapOf(hour to 56), result.hours)
        assertFalse(result.isMetarBlend)
    }
}
