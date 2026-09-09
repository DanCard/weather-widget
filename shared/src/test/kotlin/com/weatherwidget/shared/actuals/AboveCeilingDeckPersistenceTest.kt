package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Experiment B (2026-09-09): an above-ceiling deck persists across its reporting station's anchor
 * window.
 *
 * A 13,000 ft layer is invisible to the 5-minute ceilometer rows either side of the hourly METAR, so
 * without this the mid band exists at exactly one candidate per hour and the line draws a one-point
 * spike. See `findings/260909-cloud-actual-spikes-from-synoptic-blend.md`.
 */
@Category(ShortDuration::class)
class AboveCeilingDeckPersistenceTest {

    private val hour = 1_800_000_000_000L
    private val min = 60_000L

    /**
     * KSJC's real 2026-09-09 shape: a below-ceiling low layer every 5 minutes, and an hourly
     * BKN130 METAR. The Synoptic 5-minute rows carry a raw report, so `isMetar` is true on them too
     * — that is why the hourly METAR does NOT anchor the neighbouring candidates and the deck has
     * to persist on its own.
     */
    private fun station(
        id: String,
        timestamp: Long,
        low: Int?,
        mid: Int? = null,
        midBase: Int? = null,
        distanceKm: Float,
        isMetar: Boolean,
        raw: String? = null,
    ) = ObservationReading(
        stationId = id,
        stationName = id,
        timestamp = timestamp,
        temperature = 70f,
        condition = "Cloudy",
        locationLat = 37.0,
        locationLon = -122.0,
        distanceKm = distanceKm,
        api = WeatherSource.NWS.id,
        isMetar = isMetar,
        rawMetar = raw,
        cloudCoverLow = low,
        cloudCoverMid = mid,
        cloudBaseMidMeters = midBase,
        cloudVerticalKind = CloudVerticalKind.CUMULATIVE_LAYERS,
    )

    private fun blend(readings: List<ObservationReading>) =
        MetarCloudBlender.blend(readings, hour, hour + 2 * 3_600_000L)

    @Test
    fun `an above-ceiling deck anchors its station's mid band for the whole anchor window`() {
        val result = blend(
            listOf(
                // KSJC 16 km: low layer every 5 minutes, plus the hourly METAR with BKN130.
                station("KSJC", hour + 15 * min, low = 44, distanceKm = 15.9f, isMetar = true),
                station("KSJC", hour + 53 * min, low = 44, mid = 75, midBase = 3962, distanceKm = 15.9f, isMetar = true),
                station("KSJC", hour + 55 * min, low = 44, distanceKm = 15.9f, isMetar = true),
                station("KSJC", hour + 60 * min + 15 * min, low = 44, distanceKm = 15.9f, isMetar = true),
                // KNUQ 3.8 km, automated CLR — blind to the 13,000 ft deck.
                station(
                    "KNUQ",
                    hour + 35 * min,
                    low = 0,
                    distanceKm = 3.8f,
                    isMetar = true,
                    raw = "KNUQ 092035Z AUTO 35007KT 9SM CLR 32/09 A3001",
                ),
            ),
        )

        // :53 + 2 minutes and :53 + 22 minutes are both inside the anchor tolerance, so the deck
        // stays on the mid band even though those candidates' own anchors report no mid layer.
        assertEquals("the deck persists to :55", 75, result.bands[hour + 55 * min]?.mid)
        assertEquals("the deck persists into the next hour", 75, result.bands[hour + 75 * min]?.mid)
        // The drawn line is still the max of the blended bands.
        assertEquals(75, result.hours[hour + 55 * min])
        assertTrue("the persistence must be readable in app_logs", result.stats.summary().contains("stickyDeck="))
    }

    @Test
    fun `the deck does not persist beyond the anchor tolerance`() {
        val result = blend(
            listOf(
                station("KSJC", hour + 53 * min, low = 44, mid = 75, midBase = 3962, distanceKm = 15.9f, isMetar = true),
                station("KSJC", hour + 60 * min + 25 * min, low = 44, distanceKm = 15.9f, isMetar = true),
                station("KNUQ", hour + 35 * min, low = 0, distanceKm = 3.8f, isMetar = true),
            ),
        )

        // :53 + 32 minutes is outside the 30-minute reach, so the mid band is absent again.
        assertNull(result.bands[hour + 85 * min]?.mid)
    }

    @Test
    fun `a below-ceiling mid layer does not persist`() {
        val result = blend(
            listOf(
                station("KSJC", hour + 53 * min, low = 44, mid = 44, midBase = 3000, distanceKm = 15.9f, isMetar = true),
                station("KSJC", hour + 55 * min, low = 44, distanceKm = 15.9f, isMetar = true),
                station("KNUQ", hour + 35 * min, low = 0, distanceKm = 3.8f, isMetar = true),
            ),
        )

        // 3,000 m is inside every ceilometer's range, so the near station can see it; the layer is
        // an ordinary report and must not be carried forward.
        assertNull(result.bands[hour + 55 * min]?.mid)
    }
}
