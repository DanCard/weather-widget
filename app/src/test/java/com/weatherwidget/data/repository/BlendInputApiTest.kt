package com.weatherwidget.data.repository

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class BlendInputApiTest {

    @Test
    fun `a redirected source's blend inputs are its provider's rows`() {
        // 2026-09-28 Warsaw: Open-Meteo → Synoptic logged stations=[OPEN_METEO_MAIN].
        val prefs: (WeatherSource) -> WeatherSource? = {
            if (it == WeatherSource.OPEN_METEO) WeatherSource.SYNOPTIC else null
        }
        assertEquals("SYNOPTIC", blendInputApi("OPEN_METEO", prefs))
    }

    @Test
    fun `a source with its own actuals keeps its own rows`() {
        assertEquals("OPEN_METEO", blendInputApi("OPEN_METEO") { null })
        assertEquals("NWS", blendInputApi("NWS") { null })
    }

    @Test
    fun `a borrower with no preference uses the default provider`() {
        assertEquals("METAR", blendInputApi("SILURIAN") { null })
    }

    @Test
    fun `an unknown row source is left as is`() {
        assertEquals("SOMETHING_ELSE", blendInputApi("SOMETHING_ELSE") { null })
    }
}
