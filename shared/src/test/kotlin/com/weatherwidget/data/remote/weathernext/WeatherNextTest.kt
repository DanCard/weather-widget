package com.weatherwidget.data.remote.weathernext

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.ZoneId

@Category(ShortDuration::class)
class WeatherNextTest {

    @Test
    fun `domain resolution resolves California coordinates to CA`() {
        // Mountain View, CA: 37.42N, -122.09W (lon360 = 237.91)
        val domain = WeatherNextDomain.resolve(37.4166, -122.0888)
        assertEquals(WeatherNextDomain.CA, domain)
    }

    @Test
    fun `domain resolution resolves Seattle coordinates to PNW`() {
        // Seattle, WA: 47.60N, -122.33W (lon360 = 237.67)
        val domain = WeatherNextDomain.resolve(47.6062, -122.3321)
        assertEquals(WeatherNextDomain.PNW, domain)
    }

    @Test
    fun `domain resolution resolves New York coordinates to WIDE`() {
        // NYC: 40.71N, -74.01W (lon360 = 285.99)
        val domain = WeatherNextDomain.resolve(40.7128, -74.0060)
        assertEquals(WeatherNextDomain.WIDE, domain)
    }

    @Test
    fun `coordinate projection projects Mountain View to correct pixel range on CA grid`() {
        val (px, py) = WeatherNextCoordinateProjector.projectToPixel(
            lat = 37.4166,
            lon = -122.0888,
            domain = WeatherNextDomain.CA,
            figWidth = 1400f,
            figHeight = 720f,
        )

        // California domain is 215..250 lon, 27..45 lat
        // Lon fraction: (237.91 - 215) / 35 ≈ 0.654
        // Lat fraction: (37.42 - 27) / 18 ≈ 0.578
        // px should be roughly 65% across the inner axis
        assertTrue("px $px should be within [850, 950]", px in 850f..950f)
        assertTrue("py $py should be within [300, 400]", py in 300f..400f)
    }

    @Test
    fun `calculateCropBox produces full image at 1x zoom`() {
        val crop = WeatherNextCoordinateProjector.calculateCropBox(
            px = 900f,
            py = 350f,
            figWidth = 1400f,
            figHeight = 720f,
            zoom = 1.0f,
        )
        assertEquals(0f, crop.x1, 0.001f)
        assertEquals(0f, crop.y1, 0.001f)
        assertEquals(1400f, crop.x2, 0.001f)
        assertEquals(720f, crop.y2, 0.001f)
    }

    @Test
    fun `calculateCropBox centers on target point at higher zoom`() {
        val crop = WeatherNextCoordinateProjector.calculateCropBox(
            px = 900f,
            py = 350f,
            figWidth = 1400f,
            figHeight = 720f,
            zoom = 2.0f,
        )
        assertEquals(700f, crop.width, 0.001f)
        assertEquals(360f, crop.height, 0.001f)
        assertEquals(550f, crop.x1, 0.001f)
        assertEquals(170f, crop.y1, 0.001f)
    }

    @Test
    fun `formatUtcToLocal formats correctly in specific timezone`() {
        val formatted = WeatherNextForecastClient.formatUtcToLocal(
            "2026-10-20T17:00:00Z",
            zoneId = ZoneId.of("America/Los_Angeles"),
        )
        // CLDR puts a narrow no-break space before AM/PM.
        assertEquals("Tue, Oct 20, 10:00 AM", formatted.replace('\u202f', ' '))
    }

    @Test
    fun `every app locale gets its own order, no year, no zone, and the requested clock`() {
        val tags = listOf("en-US", "de", "es", "fr", "it", "pt", "pl", "ru", "uk", "tr", "in", "vi", "ja", "ko", "zh-CN", "th", "hi", "bn", "ar", "ur")
        val la = ZoneId.of("America/Los_Angeles")
        for (tag in tags) {
            val locale = java.util.Locale.forLanguageTag(tag)
            val h24 = WeatherNextForecastClient.formatUtcToLocal("2026-10-21T03:00:00Z", la, locale, use24HourClock = true)
            val h12 = WeatherNextForecastClient.formatUtcToLocal("2026-10-21T03:00:00Z", la, locale, use24HourClock = false)
            println("$tag | $h24 | $h12")
            assertTrue("$tag has no year: $h24", "2026" !in h24)
            assertTrue("$tag has no zone: $h24", "GMT" !in h24 && "PDT" !in h24)
            assertTrue("$tag 24h shows 20:00: $h24", "20:00" in h24 || "20.00" in h24)
            assertTrue("$tag 12h shows 8:00: $h12", "8:00" in h12 && "20:00" !in h12)
            assertTrue("$tag day of month: $h24", "20" in h24)
            assertTrue("$tag no year suffix: $h24", "г." !in h24 && "р." !in h24)
        }
    }
}
