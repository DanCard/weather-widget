package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the raw-METAR sky fallback added alongside `rawMetar` persistence:
 *
 * ```kotlin
 * val layers = observation.cloudLayers.ifEmpty { decodedMetar?.skyLayers ?: emptyList() }
 * ```
 *
 * The fallback is a real gap-fill — paths that never populated the JSON `cloudLayers` array can now
 * recover sky condition from the report itself. But it sits directly on top of the invariant this
 * codebase has broken twice: **an absent sky condition is "not reported", never "clear"**. A row
 * with no sky data must stay null so the graph draws grey; a row that genuinely says `CLR` must
 * store 0. See `nws_latest_endpoint_drops_cloud` and `daily_grey_cloud_means_no_row`.
 *
 * Nothing in the suite would have caught a regression from null to 0 before this file existed.
 */
@Category(ShortDuration::class)
class NwsObservationMapperCloudTest {

    private val station = NwsApi.StationInfo(
        id = "KSJC",
        name = "San Jose International Airport",
        lat = 37.36,
        lon = -121.93,
        type = NwsApi.StationType.OFFICIAL,
    )

    private fun read(
        raw: String?,
        jsonLayers: List<NwsApi.CloudLayer> = emptyList(),
        isMetar: Boolean = raw != null,
    ) = NwsObservationMapper.toReading(
        NwsApi.Observation(
            timestamp = "2026-08-23T16:53:00+00:00",
            temperatureCelsius = 20.0f,
            textDescription = "Test",
            stationName = "San Jose",
            cloudLayers = jsonLayers,
            isMetar = isMetar,
            rawMessage = raw,
        ),
        station,
        37.36,
        -121.93,
    )

    /** THE invariant. A report carrying no sky group must not become "clear". */
    @Test
    fun `raw report with no sky group leaves cloud not-reported`() {
        val reading = read("METAR KSJC 231653Z AUTO 00000KT 10SM 20/14 A2996 RMK AO2")
        assertNull("no sky group must stay null, never 0", reading.cloudCoverLow)
    }

    /** The other half of the same distinction: CLR is reported, and it means zero. */
    @Test
    fun `raw report of CLR stores zero, not null`() {
        val reading = read("METAR KSJC 231653Z AUTO 00000KT 10SM CLR 20/14 A2996 RMK AO2")
        assertEquals(0, reading.cloudCoverLow)
    }

    @Test
    fun `raw report fills sky when the json array is empty`() {
        val reading = read("METAR KSJC 231653Z 00000KT 10SM BKN012 20/14 A2996 RMK AO2")
        assertEquals("BKN below the low ceiling", 75, reading.cloudCoverLow)
    }

    /** A 25,000 ft layer is middle-band under the graph's 3 km / 8 km boundaries. */
    @Test
    fun `raw report of middle-only cloud leaves the low read null`() {
        val reading = read("METAR KSJC 231653Z 00000KT 10SM BKN250 20/14 A2996 RMK AO2")
        assertNull(reading.cloudCoverLow)
        assertEquals(75, reading.cloudCoverMid)
        assertEquals(7_620, reading.cloudBaseMidMeters)
        assertEquals(CloudVerticalKind.CUMULATIVE_LAYERS, reading.cloudVerticalKind)
    }

    /** `ifEmpty` must not let the raw parse override a populated JSON array. */
    @Test
    fun `json layers win over the raw report`() {
        val reading = read(
            raw = "METAR KSJC 231653Z 00000KT 10SM CLR 20/14 A2996 RMK AO2",
            jsonLayers = listOf(NwsApi.CloudLayer(amount = "OVC", baseMeters = 300.0)),
        )
        assertEquals("JSON is authoritative when present", 100, reading.cloudCoverLow)
    }

    /** A 5-minute ASOS row carries no rawMessage, so there is nothing to fall back to. */
    @Test
    fun `non-metar row with no layers stays not-reported`() {
        val reading = read(raw = null, isMetar = false)
        assertNull(reading.cloudCoverLow)
    }

    /** The total column stays null on every path — METAR reports cumulative layers, not total. */
    @Test
    fun `total cloud column is never populated from a metar`() {
        assertNull(read("METAR KSJC 231653Z 00000KT 10SM OVC004 20/14 A2996 RMK AO2").cloudCover)
    }
}
