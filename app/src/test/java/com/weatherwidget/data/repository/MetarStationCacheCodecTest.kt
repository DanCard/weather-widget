package com.weatherwidget.data.repository

import com.weatherwidget.data.remote.AviationWeatherStationFilter
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class MetarStationCacheCodecTest {

    private fun station(
        id: String,
        name: String = "$id site",
        lat: Double = 37.3594,
        lon: Double = -121.9244,
        distanceKm: Double = 15.9,
        elev: Double? = 13.0,
    ) = AviationWeatherStationFilter.RankedStation(
        info = NwsApi.StationInfo(id, name, lat, lon, NwsApi.StationType.OFFICIAL),
        distanceKm = distanceKm,
        elevationMeters = elev,
    )

    @Test
    fun `round-trips a station list unchanged`() {
        val original = listOf(
            station("KNUQ", "Moffett Fed Airfld", 37.4059, -122.0491, 3.8, 9.0),
            station("KSJC", "San Jose Intl", 37.3594, -121.9244, 15.9, 13.0),
        )
        val decoded = MetarStationCacheCodec.decode(MetarStationCacheCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `order is preserved so the nearest station stays first`() {
        val original = listOf(station("A", distanceKm = 1.0), station("B", distanceKm = 2.0), station("C", distanceKm = 3.0))
        val ids = MetarStationCacheCodec.decode(MetarStationCacheCodec.encode(original)).map { it.info.id }
        assertEquals(listOf("A", "B", "C"), ids)
    }

    /** Real names carry commas and slashes — `Paris/De Gaulle Arpt, ID, FR`. */
    @Test
    fun `names with commas and slashes survive`() {
        val original = listOf(station("LFPG", "Paris/De Gaulle Arpt, ID, FR", 49.015, 2.534, 12.0, 107.0))
        assertEquals(original, MetarStationCacheCodec.decode(MetarStationCacheCodec.encode(original)))
    }

    /** A tab inside a name would shift every field after it; it must be neutralised on write. */
    @Test
    fun `delimiters embedded in a name cannot corrupt the row`() {
        val original = listOf(station("KX", "we\tird|name"))
        val decoded = MetarStationCacheCodec.decode(MetarStationCacheCodec.encode(original))
        assertEquals(1, decoded.size)
        assertEquals("KX", decoded.single().info.id)
        assertEquals("we ird name", decoded.single().info.name)
        assertEquals(37.3594, decoded.single().info.lat, 1e-9)
    }

    @Test
    fun `missing elevation round-trips as null`() {
        val decoded = MetarStationCacheCodec.decode(MetarStationCacheCodec.encode(listOf(station("KX", elev = null))))
        assertNull(decoded.single().elevationMeters)
    }

    @Test
    fun `negative coordinates and distances survive`() {
        val original = listOf(station("SAEZ", "Buenos Aires", -34.822, -58.535, 42.5, 20.0))
        assertEquals(original, MetarStationCacheCodec.decode(MetarStationCacheCodec.encode(original)))
    }

    @Test
    fun `every decoded station is OFFICIAL`() {
        val decoded = MetarStationCacheCodec.decode(MetarStationCacheCodec.encode(listOf(station("KX"))))
        assertEquals(NwsApi.StationType.OFFICIAL, decoded.single().info.type)
    }

    // ---- corrupt input degrades, never throws ----

    @Test
    fun `an empty cache decodes to nothing`() {
        assertTrue(MetarStationCacheCodec.decode("").isEmpty())
    }

    @Test
    fun `truncated and unparseable rows are skipped, good rows survive`() {
        val good = MetarStationCacheCodec.encode(listOf(station("KGOOD")))
        val corrupt = "KSHORT\tname\t37.0|KBAD\tname\tnotanumber\t-121.0\t5.0\t13.0|$good"
        val decoded = MetarStationCacheCodec.decode(corrupt)
        assertEquals(listOf("KGOOD"), decoded.map { it.info.id })
    }

    @Test
    fun `a blank id is not a station`() {
        assertTrue(MetarStationCacheCodec.decode("\tname\t37.0\t-121.0\t5.0\t13.0").isEmpty())
    }

    /** `Double.toString` is locale-independent, but pin it: a comma decimal would break decode. */
    @Test
    fun `encoding is locale-independent`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            val original = listOf(station("LFPG", "CDG", 49.015, 2.534, 12.5, 107.0))
            assertEquals(original, MetarStationCacheCodec.decode(MetarStationCacheCodec.encode(original)))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
