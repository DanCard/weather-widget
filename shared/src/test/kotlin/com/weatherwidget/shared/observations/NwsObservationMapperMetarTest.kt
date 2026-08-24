package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * What the mapper does — and deliberately does NOT do — with a decoded METAR.
 *
 * Two enrichments were removed after measurement (plan 260823 §2.5); the tests that asserted them
 * are inverted here rather than deleted, so re-adding either fails loudly instead of silently
 * shipping.
 */
@Category(ShortDuration::class)
class NwsObservationMapperMetarTest {

    private val station = NwsApi.StationInfo(
        id = "KSJC",
        name = "San Jose International Airport",
        lat = 37.36,
        lon = -121.93,
        type = NwsApi.StationType.OFFICIAL,
    )

    private fun read(obs: NwsApi.Observation) =
        NwsObservationMapper.toReading(obs, station, 37.36, -121.93)

    /**
     * `api.weather.gov` decodes the T-group itself — a KSJC `:53` row arrives as 30.6, not 31 — so
     * the payload temperature is authoritative even when the remarks disagree. The disagreement
     * below is synthetic (20.0 vs a T-group of 20.4) and exists only to prove which side wins.
     */
    @Test
    fun `payload temperature wins over the remarks T-group`() {
        val raw = "METAR KSJC 231653Z 00000KT 10SM SCT080 20/14 A2996 RMK AO2 SLP144 T02040144"
        val reading = read(
            NwsApi.Observation(
                timestamp = "2026-08-23T16:53:00+00:00",
                temperatureCelsius = 20.0f,
                textDescription = "Partly Cloudy",
                stationName = "San Jose",
                isMetar = true,
                rawMessage = raw,
            ),
        )

        // 20.0°C -> 68.0°F. The T-group's 20.4°C (68.72°F) must NOT be substituted.
        assertEquals(68.0f, reading.temperature, 0.01f)
        assertEquals(raw, reading.rawMetar)
        assertTrue(reading.isMetar)
    }

    /**
     * `maxTemperatureLast24Hours` is a ROLLING 24-hour extreme. The METAR `4sTTTTsTTTT` group is the
     * LOCAL CALENDAR-DAY extreme for the day that just ended, emitted once daily around 01:00 local.
     * Backfilling one from the other is an off-by-one day once
     * `ObservationResolver.officialExtremesToDailyEntities` gains a caller.
     */
    @Test
    fun `24-hour remark extremes do not backfill the rolling columns`() {
        val raw = "METAR KSJC 230753Z AUTO 35003KT 10SM CLR 17/13 A2997 RMK AO2 SLP147 T01720133 402610156"
        val reading = read(
            NwsApi.Observation(
                timestamp = "2026-08-23T08:00:00+00:00",
                temperatureCelsius = 17.2f,
                textDescription = "Clear",
                stationName = "San Jose",
                maxTempLast24hCelsius = null,
                minTempLast24hCelsius = null,
                isMetar = true,
                rawMessage = raw,
            ),
        )

        assertNull("402610156 is Aug 22's calendar-day max, not a rolling 24h max", reading.maxTempLast24h)
        assertNull(reading.minTempLast24h)
        assertEquals("the raw report is still preserved for later re-decoding", raw, reading.rawMetar)
    }

    /** The payload's own rolling extremes still pass through untouched. */
    @Test
    fun `payload rolling extremes are preserved`() {
        val reading = read(
            NwsApi.Observation(
                timestamp = "2026-08-23T16:53:00+00:00",
                temperatureCelsius = 20.0f,
                textDescription = "Clear",
                stationName = "San Jose",
                maxTempLast24hCelsius = 25.0f,
                minTempLast24hCelsius = 12.0f,
                isMetar = true,
                rawMessage = "METAR KSJC 231653Z 00000KT 10SM CLR 20/14 A2996 RMK AO2 402610156",
            ),
        )

        assertEquals(77.0f, reading.maxTempLast24h!!, 0.01f)
        assertEquals(53.6f, reading.minTempLast24h!!, 0.01f)
    }

    /**
     * Precip DOES fall back. `Pxxxx` is "since the last hourly report" — the same window and units
     * as `precipitationLastHour`, so it is a legitimate gap-fill rather than a semantic swap.
     */
    @Test
    fun `hourly precip is backfilled from the P-group when the payload has none`() {
        val raw = "METAR KSJC 231653Z 00000KT 5SM RA OVC012 20/14 A2996 RMK AO2 T02000144 P0005"
        val reading = read(
            NwsApi.Observation(
                timestamp = "2026-08-23T16:53:00+00:00",
                temperatureCelsius = 20.0f,
                textDescription = "Rain",
                stationName = "San Jose",
                precipLastHourMm = null,
                isMetar = true,
                rawMessage = raw,
            ),
        )

        // P0005 -> 0.05 in -> 1.27 mm
        assertEquals(1.27f, reading.precipAmountMm!!, 0.01f)
    }

    @Test
    fun `payload precip wins over the P-group`() {
        val reading = read(
            NwsApi.Observation(
                timestamp = "2026-08-23T16:53:00+00:00",
                temperatureCelsius = 20.0f,
                textDescription = "Rain",
                stationName = "San Jose",
                precipLastHourMm = 3.0f,
                isMetar = true,
                rawMessage = "METAR KSJC 231653Z 00000KT 5SM RA OVC012 20/14 A2996 RMK AO2 P0005",
            ),
        )

        assertEquals(3.0f, reading.precipAmountMm!!, 0.01f)
    }

    /**
     * The 5-minute ASOS rows the observations endpoint interleaves carry `"rawMessage": ""` — a
     * present-but-empty string. `rawMetar` must come back NULL for them, per its own KDoc, so that
     * `rawMetar IS NOT NULL` remains a usable "is this a METAR" predicate. Normalized in NwsApi;
     * asserted here at the mapper because that is where the column is populated.
     */
    @Test
    fun `blank raw message does not persist as an empty string`() {
        val reading = read(
            NwsApi.Observation(
                timestamp = "2026-08-23T16:35:00+00:00",
                temperatureCelsius = 30.0f,
                textDescription = "Clear",
                stationName = "San Jose",
                isMetar = false,
                rawMessage = null,
            ),
        )

        assertNull(reading.rawMetar)
    }

    /** A station with no remarks at all (KNUQ) must map exactly as it did before the decoder. */
    @Test
    fun `report with bare remarks maps to the payload values`() {
        val raw = "KNUQ 231655Z AUTO 34005KT 10SM CLR 19/15 A2996 RMK AO2"
        val reading = read(
            NwsApi.Observation(
                timestamp = "2026-08-23T16:55:00+00:00",
                temperatureCelsius = 19.0f,
                textDescription = "Clear",
                stationName = "Moffett",
                isMetar = true,
                rawMessage = raw,
            ),
        )

        assertEquals(66.2f, reading.temperature, 0.01f)
        assertNull(reading.maxTempLast24h)
        assertNull(reading.precipAmountMm)
        assertEquals(raw, reading.rawMetar)
    }
}
