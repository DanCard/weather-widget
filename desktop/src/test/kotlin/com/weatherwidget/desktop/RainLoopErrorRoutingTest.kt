package com.weatherwidget.desktop

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.rainmap.RainLoopLoadError
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class RainLoopErrorRoutingTest {
    @Test
    fun `Open-Meteo shown or chosen from an error opens its map, otherwise WeatherNext`() {
        assertTrue(simLoopOpensOpenMeteo(WeatherSource.OPEN_METEO.id, forcedOpenMeteo = false))
        assertTrue(simLoopOpensOpenMeteo(WeatherSource.SILURIAN.id, forcedOpenMeteo = true))
        assertFalse(simLoopOpensOpenMeteo(WeatherSource.SILURIAN.id, forcedOpenMeteo = false))
        assertFalse(simLoopOpensOpenMeteo(WeatherSource.NWS.id, forcedOpenMeteo = false))
    }

    @Test
    fun `error text names the host and what failed`() {
        val gh = "ajoros.github.io"
        assertEquals(
            "ajoros.github.io no longer serves the rain loop (HTTP 404). It may have been taken down.",
            RainLoopLoadError.gone(gh, 404).englishText(),
        )
        assertEquals("ajoros.github.io answered HTTP 503.", RainLoopLoadError(RainLoopLoadError.Kind.HTTP, gh, 503).englishText())
        assertEquals(
            "Couldn't reach api.open-meteo.com — check the network connection.",
            RainLoopLoadError(RainLoopLoadError.Kind.OFFLINE, "api.open-meteo.com").englishText(),
        )
        assertEquals(
            "None of the rain loop's 100 images downloaded from ajoros.github.io.",
            RainLoopLoadError.noFrames(gh, 100).englishText(),
        )
        // Desktop's own copy printed a missing status as "HTTP null"; the shared line reads 404.
        assertEquals(
            "ajoros.github.io no longer serves the rain loop (HTTP 404). It may have been taken down.",
            RainLoopLoadError(RainLoopLoadError.Kind.GONE, gh).englishText(),
        )
    }
}
