package com.weatherwidget.widget

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DefaultVisibleSourcesTest {
    private val release = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN)

    @Test
    fun `debug build with a baked Google key defaults to Google first`() {
        assertEquals(
            listOf(
                WeatherSource.GOOGLE_WEATHER,
                WeatherSource.NWS,
                WeatherSource.OPEN_METEO,
                WeatherSource.SILURIAN,
                WeatherSource.TOMORROW_IO,
            ),
            DefaultVisibleSources.forBuild(debug = true, hasBakedGoogleKey = true),
        )
    }

    @Test
    fun `debug build without a Google key leaves Google off`() {
        assertEquals(
            listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN, WeatherSource.TOMORROW_IO),
            DefaultVisibleSources.forBuild(debug = true, hasBakedGoogleKey = false),
        )
    }

    @Test
    fun `release build never defaults to Google, key or not`() {
        assertEquals(release, DefaultVisibleSources.forBuild(debug = false, hasBakedGoogleKey = false))
        assertEquals(release, DefaultVisibleSources.forBuild(debug = false, hasBakedGoogleKey = true))
    }
}
