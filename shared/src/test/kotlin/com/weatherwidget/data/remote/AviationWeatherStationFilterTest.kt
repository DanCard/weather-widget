package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class AviationWeatherStationFilterTest {

    private fun candidate(
        id: String,
        lat: Double,
        lon: Double,
        siteTypes: List<String> = listOf("METAR"),
        name: String = "$id site",
        elev: Double? = 10.0,
    ) = AviationWeatherStationFilter.Candidate(id, name, lat, lon, elev, siteTypes, "US")

    /** Observed live: `AAMC1` (Alameda) comes back with `"siteType": []` and reports no METARs. */
    @Test
    fun `sites with no METAR product are excluded`() {
        val result = AviationWeatherStationFilter.nearest(
            listOf(
                candidate("AAMC1", 37.772, -122.298, siteTypes = emptyList()),
                candidate("KHWD", 37.659, -122.121, siteTypes = listOf("METAR")),
                candidate("KLVK", 37.693, -121.815, siteTypes = listOf("METAR", "TAF")),
            ),
            37.4, -122.1,
        )
        assertEquals(listOf("KHWD", "KLVK"), result.map { it.info.id })
    }

    @Test
    fun `siteType match is case-insensitive`() {
        val result = AviationWeatherStationFilter.nearest(
            listOf(candidate("KSJC", 37.36, -121.92, siteTypes = listOf("metar"))), 37.4, -122.1,
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `ranked by true distance, not input order`() {
        val result = AviationWeatherStationFilter.nearest(
            listOf(
                candidate("FAR", 38.5, -122.8),
                candidate("NEAR", 37.41, -122.11),
                candidate("MID", 37.66, -122.12),
            ),
            37.4, -122.1,
        )
        assertEquals(listOf("NEAR", "MID", "FAR"), result.map { it.info.id })
    }

    @Test
    fun `capped at the limit`() {
        val many = (1..12).map { candidate("K$it", 37.4 + it * 0.01, -122.1) }
        assertEquals(5, AviationWeatherStationFilter.nearest(many, 37.4, -122.1).size)
        assertEquals(2, AviationWeatherStationFilter.nearest(many, 37.4, -122.1, limit = 2).size)
    }

    @Test
    fun `fewer than the limit returns what exists`() {
        val result = AviationWeatherStationFilter.nearest(
            listOf(candidate("KONE", 37.41, -122.11)), 37.4, -122.1,
        )
        assertEquals(1, result.size)
    }

    /**
     * The blend re-reads this list every cycle. Two equidistant stations that swap places between
     * cycles would hand the IDW a different input set for the same location.
     */
    @Test
    fun `equidistant stations break ties deterministically`() {
        val a = candidate("KAAA", 37.5, -122.1)
        val b = candidate("KBBB", 37.3, -122.1)
        val forward = AviationWeatherStationFilter.nearest(listOf(a, b), 37.4, -122.1)
        val reversed = AviationWeatherStationFilter.nearest(listOf(b, a), 37.4, -122.1)
        assertEquals(forward.map { it.info.id }, reversed.map { it.info.id })
        assertEquals(listOf("KAAA", "KBBB"), forward.map { it.info.id })
    }

    @Test
    fun `duplicate ids collapse to one`() {
        val result = AviationWeatherStationFilter.nearest(
            listOf(candidate("KSJC", 37.36, -121.92), candidate("KSJC", 37.36, -121.92)),
            37.4, -122.1,
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `non-finite coordinates are dropped rather than ranked`() {
        val result = AviationWeatherStationFilter.nearest(
            listOf(
                candidate("BAD", Double.NaN, -122.1),
                candidate("GOOD", 37.41, -122.11),
            ),
            37.4, -122.1,
        )
        assertEquals(listOf("GOOD"), result.map { it.info.id })
    }

    /**
     * Every station in this feed is an airport reporting station. Typing one PERSONAL would apply
     * `DEFAULT_PERSONAL_STATION_DISCOUNT` in the blend and quietly downweight real data.
     */
    @Test
    fun `all stations are typed OFFICIAL`() {
        val result = AviationWeatherStationFilter.nearest(
            listOf(candidate("KSJC", 37.36, -121.92)), 37.4, -122.1,
        )
        assertEquals(NwsApi.StationType.OFFICIAL, result.single().info.type)
    }

    @Test
    fun `blank name falls back to the id`() {
        val result = AviationWeatherStationFilter.nearest(
            listOf(candidate("KSJC", 37.36, -121.92, name = "  ")), 37.4, -122.1,
        )
        assertEquals("KSJC", result.single().info.name)
    }

    @Test
    fun `distance matches the known KNUQ to KSJC separation`() {
        // Moffett Field to San Jose International, ~15.9 km per the observations table.
        val km = AviationWeatherStationFilter.distanceKm(37.4059, -122.0491, 37.3594, -121.9244)
        assertTrue("expected ~11-13 km, got $km", km in 10.0..14.0)
    }

    @Test
    fun `elevation is carried through for later lapse work`() {
        val result = AviationWeatherStationFilter.nearest(
            listOf(candidate("KLVK", 37.693, -121.815, elev = 120.0)), 37.4, -122.1,
        )
        assertEquals(120.0, result.single().elevationMeters!!, 1e-9)
    }
}
