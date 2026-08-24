package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class AviationWeatherBboxTest {

    private fun parts(bbox: String) = bbox.split(",").map { it.toDouble() }

    @Test
    fun `box is south-west corner first`() {
        val (lat0, lon0, lat1, lon1) = parts(AviationWeatherBbox.forLocation(37.4, -122.1))
        assertTrue("lat0 < lat1", lat0 < lat1)
        assertTrue("lon0 < lon1", lon0 < lon1)
    }

    /**
     * The correction that matters: a degree of longitude is ~half a degree of latitude at 60°N, so
     * an uncorrected square box searches a much narrower strip of ground the further north you go.
     */
    @Test
    fun `longitude half-width widens with latitude`() {
        val equator = AviationWeatherBbox.halfWidthDegrees(0.0, 0.35)
        val sixty = AviationWeatherBbox.halfWidthDegrees(60.0, 0.35)
        assertEquals(0.35, equator, 0.001)
        assertEquals("cos(60) = 0.5, so twice as wide in degrees", 0.70, sixty, 0.01)
    }

    @Test
    fun `southern hemisphere scales the same as northern`() {
        assertEquals(
            AviationWeatherBbox.halfWidthDegrees(60.0, 0.35),
            AviationWeatherBbox.halfWidthDegrees(-60.0, 0.35),
            1e-9,
        )
    }

    /** `1 / cos(lat)` diverges at the pole; the width must saturate rather than explode. */
    @Test
    fun `polar latitudes clamp the half-width`() {
        val nearPole = AviationWeatherBbox.halfWidthDegrees(89.999, 0.35)
        assertTrue("finite", nearPole.isFinite())
        assertTrue("clamped to at most a hemisphere", nearPole <= 180.0)
    }

    @Test
    fun `latitude never exceeds the poles`() {
        val (lat0, _, lat1, _) = parts(AviationWeatherBbox.forLocation(89.9, 10.0, step = 3))
        assertTrue("lat1 <= 90 (got $lat1)", lat1 <= 90.0)
        assertTrue("lat0 >= -90 (got $lat0)", lat0 >= -90.0)
    }

    @Test
    fun `longitude never exceeds the antimeridian`() {
        val (_, lon0, _, lon1) = parts(AviationWeatherBbox.forLocation(0.0, 179.8))
        assertTrue("lon1 <= 180 (got $lon1)", lon1 <= 180.0)
        val (_, wLon0, _, _) = parts(AviationWeatherBbox.forLocation(0.0, -179.8))
        assertTrue("lon0 >= -180 (got $wLon0)", wLon0 >= -180.0)
    }

    @Test
    fun `expansion ladder grows then saturates`() {
        val steps = (0..6).map { AviationWeatherBbox.halfHeightForStep(it) }
        steps.zipWithNext().forEach { (a, b) -> assertTrue("non-decreasing", b >= a) }
        assertEquals(AviationWeatherBbox.BASE_HALF_DEGREES, steps.first(), 1e-9)
        assertEquals(AviationWeatherBbox.MAX_HALF_DEGREES, steps.last(), 1e-9)
        assertTrue("saturation is detectable", AviationWeatherBbox.isMaxStep(6))
        assertTrue("base step is not saturated", !AviationWeatherBbox.isMaxStep(0))
    }

    /** `String.format("%.4f")` emits `48,5` under a comma-decimal locale and the API rejects it. */
    @Test
    fun `formatting is locale-independent`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            val bbox = AviationWeatherBbox.forLocation(48.85, 2.35)
            assertTrue("no comma decimals in $bbox", Regex("""^-?[\d.]+(,-?[\d.]+){3}$""").matches(bbox))
            assertEquals(4, bbox.split(",").size)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    private operator fun <T> List<T>.component4(): T = this[3]
}
