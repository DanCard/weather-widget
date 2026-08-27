package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.CloudHourBucket
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * [CeilometerBlindSpot] driven through the real station blend, on the scene measured 2026-08-27.
 *
 * The unit test pins the rule; this pins that the rule is actually reached by
 * [MetarCloudBlender.blend], survives IDW weighting, and shows up in the stats a reader would
 * consult from `app_logs`.
 */
@Category(ShortDuration::class)
class CeilometerBlindSpotBlendIntegrationTest {

    private val hour = CloudHourBucket.startMsOf(1_787_602_800_000L)
    private val lat = 37.417
    private val lon = -122.089

    private fun station(
        id: String,
        distanceKm: Float,
        cover: Int?,
        raw: String?,
        isMetar: Boolean,
        lowBase: Int? = null,
        midBase: Int? = null,
    ) = ObservationReading(
        stationId = id,
        stationName = id,
        timestamp = hour,
        temperature = 70f,
        condition = "Cloudy",
        locationLat = lat,
        locationLon = lon,
        distanceKm = distanceKm,
        stationType = "OFFICIAL",
        api = WeatherSource.NWS.id,
        rawMetar = raw,
        isMetar = isMetar,
        cloudCoverLow = if (lowBase != null || cover == 0) cover else null,
        cloudCoverMid = if (midBase != null) cover else null,
        cloudBaseLowMeters = lowBase,
        cloudBaseMidMeters = midBase,
        cloudVerticalKind = CloudVerticalKind.CUMULATIVE_LAYERS,
    )

    private fun blend(rows: List<ObservationReading>) = runBlocking {
        MetarCloudBlender.fromSiteRows(
            startMs = hour,
            endMs = hour + 3_600_000L,
            sourceId = WeatherSource.NWS.id,
            readSiteRows = { _, _ -> rows },
        )
    }

    /**
     * The report, verbatim: KNUQ `CLR` at 3.8 km (69% of the IDW weight), KPAO `BKN180` — a broken
     * deck at 18,000 ft — at 6.1 km, KSJC the same at 15.9 km. Blending KNUQ's silence as a vote
     * produced 2%.
     */
    @Test
    fun `an eighteen-thousand-foot deck is not outvoted by the nearest ceilometer`() {
        val result = blend(
            listOf(
                station("KNUQ", 3.8f, 0, "KNUQ 272035Z AUTO 36007KT 10SM CLR 24/16 A2998 RMK AO2", isMetar = true),
                station("KPAO", 6.1f, 75, "KPAO 272047Z 34008KT 10SM BKN180 23/17 A2999", isMetar = true, midBase = 5486),
                station("KSJC", 15.9f, 75, "KSJC 272053Z 31009KT 10SM BKN100 24/15 A2998", isMetar = true, midBase = 3048),
            ),
        )

        val value = result.hours[hour]
        assertTrue("expected the deck to survive the blend, got $value", value != null && value >= 70)
        assertEquals(1, result.stats.ceilometerBlindBuckets)
        assertTrue("the decision must be readable in app_logs", result.stats.summary().contains("ceilometerBlind=1"))
    }

    /**
     * The case the rule must NOT fire on: an 800 ft deck is inside every ceilometer's range, so
     * KNUQ's 0 is a real measurement of a patchy marine layer and keeps its weight.
     */
    @Test
    fun `a low deck leaves the nearest station's clear reading fully weighted`() {
        val result = blend(
            listOf(
                station("KNUQ", 3.8f, 0, "KNUQ 272035Z AUTO 36007KT 10SM CLR 24/16 A2998 RMK AO2", isMetar = true),
                station("KPAO", 6.1f, 75, "KPAO 272047Z 34008KT 4SM BKN008 17/16 A2999", isMetar = true, lowBase = 244),
            ),
        )

        val value = result.hours[hour]
        assertTrue("the near clear reading must still dominate, got $value", value != null && value < 30)
        assertEquals(0, result.stats.ceilometerBlindBuckets)
    }

    /** A human observer's SKC is a whole-sky assessment and is never displaced. */
    @Test
    fun `an SKC station keeps its weight against a high deck`() {
        val result = blend(
            listOf(
                station("KPAO", 3.0f, 0, "KPAO 271947Z 33007KT 10SM SKC 23/16 A2998", isMetar = true),
                station("KSJC", 15.9f, 75, "KSJC 272053Z 31009KT 10SM BKN180 24/15 A2998", isMetar = true, midBase = 5486),
            ),
        )

        val value = result.hours[hour]
        assertTrue("a human clear observation must still count, got $value", value != null && value < 30)
        assertEquals(0, result.stats.ceilometerBlindBuckets)
    }
}
