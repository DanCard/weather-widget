package com.weatherwidget.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class StationHistoryUrlTest {
    @Test
    fun `official NWS station links to its time-series page`() {
        assertEquals(
            "https://www.weather.gov/wrh/timeseries?site=KSFO",
            StationHistoryUrl.forStation(WeatherSource.NWS.id, "KSFO"),
        )
    }

    @Test
    fun `personal NWS station links to its time-series page`() {
        assertEquals(
            "https://www.weather.gov/wrh/timeseries?site=AW020",
            StationHistoryUrl.forStation(WeatherSource.NWS.id, "AW020"),
        )
    }

    @Test
    fun `synthetic blend has no link`() {
        assertNull(StationHistoryUrl.forStation(WeatherSource.NWS.id, "NWS_BLEND"))
    }

    @Test
    fun `blank station id has no link`() {
        assertNull(StationHistoryUrl.forStation(WeatherSource.NWS.id, ""))
    }

    @Test
    fun `non-NWS sources have no link`() {
        assertNull(StationHistoryUrl.forStation(WeatherSource.OPEN_METEO.id, "KSFO"))
        assertNull(StationHistoryUrl.forStation(WeatherSource.SILURIAN.id, "anything"))
    }
}
