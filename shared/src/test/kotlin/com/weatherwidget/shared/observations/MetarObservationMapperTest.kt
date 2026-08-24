package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.AviationWeatherApi
import com.weatherwidget.data.remote.AviationWeatherStationFilter
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class MetarObservationMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun station(id: String, distanceKm: Double = 3.8) =
        AviationWeatherStationFilter.RankedStation(
            info = NwsApi.StationInfo(id, "$id site", 37.4059, -122.0491, NwsApi.StationType.OFFICIAL),
            distanceKm = distanceKm,
            elevationMeters = 9.0,
        )

    private fun row(
        id: String = "KSJC",
        temp: Float? = 20.0f,
        raw: String? = null,
        clouds: List<NwsApi.CloudLayer> = emptyList(),
        obsMillis: Long = 1_787_503_980_000L,
    ) = AviationWeatherApi.MetarRow(
        stationId = id,
        stationName = "$id site",
        observedAtMillis = obsMillis,
        latitude = 37.3594,
        longitude = -121.9244,
        elevationMeters = 13.0,
        temperatureCelsius = temp,
        dewpointCelsius = 14.4f,
        seaLevelPressureHpa = 1014.4f,
        cloudLayers = clouds,
        rawOb = raw,
        isSpeci = false,
    )

    @Test
    fun `provenance is METAR, never NWS`() {
        val reading = MetarObservationMapper.toReading(row(), station("KSJC"), 37.4, -122.1)!!
        assertEquals("METAR", reading.api)
        assertTrue("must not masquerade as NWS", reading.api != "NWS")
    }

    @Test
    fun `temperature converts to fahrenheit and the site coordinate is the fetch site`() {
        val reading = MetarObservationMapper.toReading(row(temp = 20.0f), station("KSJC"), 37.4, -122.1)!!
        assertEquals(68.0f, reading.temperature, 0.01f)
        assertEquals(37.4, reading.locationLat, 1e-9)
        assertEquals(-122.1, reading.locationLon, 1e-9)
    }

    /** Storing 0 °C for a missing temperature would poison the blend worse than a missing row. */
    @Test
    fun `a row with no temperature is dropped`() {
        assertNull(MetarObservationMapper.toReading(row(temp = null), station("KSJC"), 37.4, -122.1))
    }

    @Test
    fun `raw report is preserved for later re-decoding`() {
        val raw = "METAR KSJC 231653Z 00000KT 10SM SCT080 20/14 A2996 RMK AO2 SLP144 T02000144"
        val reading = MetarObservationMapper.toReading(row(raw = raw), station("KSJC"), 37.4, -122.1)!!
        assertEquals(raw, reading.rawMetar)
    }

    /**
     * The `4sTTTTsTTTT` group is the LOCAL CALENDAR-DAY extreme for the day that just ended, not a
     * rolling 24-hour value. Same off-by-one this codebase already removed from the NWS path.
     */
    @Test
    fun `24-hour remark extremes never populate the rolling columns`() {
        val raw = "METAR KSJC 230753Z AUTO 35003KT 10SM CLR 17/13 A2997 RMK AO2 SLP147 T01720133 402610156"
        val reading = MetarObservationMapper.toReading(row(raw = raw), station("KSJC"), 37.4, -122.1)!!
        assertNull(reading.maxTempLast24h)
        assertNull(reading.minTempLast24h)
    }

    /** `Pxxxx` is "since the last hourly report" — the same window NWS's precipitationLastHour uses. */
    @Test
    fun `hourly precip comes from the P-group`() {
        val raw = "METAR KSJC 231653Z 00000KT 5SM RA OVC012 20/14 A2996 RMK AO2 P0005"
        val reading = MetarObservationMapper.toReading(row(raw = raw), station("KSJC"), 37.4, -122.1)!!
        assertEquals(1.27f, reading.precipAmountMm!!, 0.01f)
    }

    @Test
    fun `payload clouds win over the raw report`() {
        val reading = MetarObservationMapper.toReading(
            row(
                raw = "METAR KSJC 231653Z 00000KT 10SM CLR 20/14 A2996 RMK AO2",
                clouds = listOf(NwsApi.CloudLayer("OVC", 300.0)),
            ),
            station("KSJC"), 37.4, -122.1,
        )!!
        assertEquals(100, reading.cloudCoverLow)
    }

    /** THE invariant, on this path too: absent sky is "not reported", never "clear". */
    @Test
    fun `no clouds anywhere leaves the low read null`() {
        val reading = MetarObservationMapper.toReading(
            row(raw = "METAR KSJC 231653Z AUTO 00000KT 10SM 20/14 A2996 RMK AO2"),
            station("KSJC"), 37.4, -122.1,
        )!!
        assertNull(reading.cloudCoverLow)
    }

    @Test
    fun `total cloud column is never populated`() {
        val reading = MetarObservationMapper.toReading(
            row(clouds = listOf(NwsApi.CloudLayer("OVC", 120.0))), station("KSJC"), 37.4, -122.1,
        )!!
        assertNull(reading.cloudCover)
    }

    /**
     * The endpoint serves METARs and SPECIs only — there is no ASOS 5-minute interleave here, which
     * is the one thing `isMetar` exists to separate. MetarCloudBlender prefers METAR rows for cloud,
     * so the default `false` would quietly deny these rows that preference.
     */
    @Test
    fun `every row is flagged as a METAR, SPECI included`() {
        val metar = MetarObservationMapper.toReading(row(), station("KSJC"), 37.4, -122.1)!!
        assertTrue(metar.isMetar)
        val speci = MetarObservationMapper.toReading(
            row().copy(isSpeci = true), station("KSJC"), 37.4, -122.1,
        )!!
        assertTrue("a SPECI is a real report too", speci.isMetar)
    }

    @Test
    fun `stations are OFFICIAL and never flagged as a web fallback`() {
        val reading = MetarObservationMapper.toReading(row(), station("KSJC"), 37.4, -122.1)!!
        assertEquals("OFFICIAL", reading.stationType)
        assertTrue(!reading.isWebFallback)
    }

    // ---------- integration: parse -> filter -> map -> decode -> sky cover ----------

    /**
     * Five classes end to end on a real captured payload, per the project's definition of an
     * integration test. Domestic and international rows go through the identical path.
     */
    @Test
    fun `end to end from captured payload to observation readings`() {
        val stationBody = """
            [{"id":"KSJC","site":"San Jose Intl","lat":37.3594,"lon":-121.9244,"elev":13,"country":"US","siteType":["METAR","TAF"]},
             {"id":"AAMC1","site":"Alameda","lat":37.772,"lon":-122.298,"elev":6,"country":"US","siteType":[]},
             {"id":"LFPG","site":"Paris/De Gaulle","lat":49.015,"lon":2.534,"elev":107,"country":"FR","siteType":["METAR"]}]
        """.trimIndent()
        val metarBody = """
            [{"icaoId":"KSJC","obsTime":1787503980,"temp":20,"dewp":14.4,"visib":"10+","wdir":0,"slp":1014.4,
              "lat":37.3594,"lon":-121.9244,"elev":13,"name":"San Jose Intl","metarType":"METAR",
              "clouds":[{"cover":"SCT","base":8000},{"cover":"BKN","base":1000}],
              "rawOb":"METAR KSJC 231653Z 00000KT 10SM SCT080 BKN010 20/14 A2996 RMK AO2 SLP144 T02000144"},
             {"icaoId":"LFPG","obsTime":1787504400,"temp":23,"dewp":5,"wdir":"VRB",
              "lat":49.015,"lon":2.534,"elev":107,"name":"Paris/De Gaulle","metarType":"METAR",
              "clouds":[],
              "rawOb":"METAR LFPG 231700Z 06012KT CAVOK 23/05 Q1021 NOSIG"}]
        """.trimIndent()

        val candidates = (AviationWeatherApi.parseStationInfo(json, stationBody) as FetchOutcome.Success).value
        val ranked = AviationWeatherStationFilter.nearest(candidates, 37.4, -122.1)
            .associateBy { it.info.id }
        assertTrue("the non-METAR site is filtered out", "AAMC1" !in ranked)

        val rows = (AviationWeatherApi.parseMetars(json, metarBody) as FetchOutcome.Success).value
        val readings = rows.mapNotNull { r ->
            val st = ranked[r.stationId] ?: AviationWeatherStationFilter.nearest(
                candidates.filter { it.id == r.stationId }, 37.4, -122.1,
            ).firstOrNull()
            st?.let { MetarObservationMapper.toReading(r, it, 37.4, -122.1) }
        }.associateBy { it.stationId }

        val sjc = readings.getValue("KSJC")
        assertEquals(68.0f, sjc.temperature, 0.01f)
        assertEquals("METAR", sjc.api)
        // BKN at 1,000 ft is below the 2,000 m low-layer ceiling; SCT at 8,000 ft is above it.
        assertEquals(75, sjc.cloudCoverLow)
        assertTrue(sjc.rawMetar!!.contains("T02000144"))

        val cdg = readings.getValue("LFPG")
        assertEquals("international rows survive the same path", 73.4f, cdg.temperature, 0.01f)
        // CAVOK in the raw report is a positive "no significant cloud" — 0, not null.
        assertEquals(0, cdg.cloudCoverLow)
    }
}
