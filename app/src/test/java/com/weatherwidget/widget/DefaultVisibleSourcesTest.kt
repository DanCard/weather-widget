package com.weatherwidget.widget

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DefaultVisibleSourcesTest {
    private val debug = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN, WeatherSource.TOMORROW_IO)
    private val release = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN)

    @Test
    fun `debug build defaults to standard sources and Tomorrow io`() {
        assertEquals(debug, DefaultVisibleSources.forBuild(debug = true, hasBakedGoogleKey = true))
        assertEquals(debug, DefaultVisibleSources.forBuild(debug = true, hasBakedGoogleKey = false))
    }

    @Test
    fun `release build never defaults to Google, key or not`() {
        assertEquals(release, DefaultVisibleSources.forBuild(debug = false, hasBakedGoogleKey = false))
        assertEquals(release, DefaultVisibleSources.forBuild(debug = false, hasBakedGoogleKey = true))
    }
}
