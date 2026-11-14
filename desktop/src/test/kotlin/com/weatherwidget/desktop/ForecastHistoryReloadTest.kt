package com.weatherwidget.desktop

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

/** A data-changed reload must not blank History of Forecasts; a different view still shows Loading. */
@Category(ShortDuration::class)
class ForecastHistoryReloadTest {
    private val day = LocalDate.of(2026, 11, 14)
    private val view = ForecastHistoryReload.View(day, WeatherSource.NWS, 37.42, -122.08)

    @Test
    fun firstLoadShowsLoading() {
        assertTrue(ForecastHistoryReload.showsLoading(null, view))
    }

    @Test
    fun sameViewReloadKeepsContent() {
        assertFalse(ForecastHistoryReload.showsLoading(view, view.copy()))
    }

    @Test
    fun differentDateSourceOrLocationShowsLoading() {
        assertTrue(ForecastHistoryReload.showsLoading(view, view.copy(date = day.plusDays(1))))
        assertTrue(ForecastHistoryReload.showsLoading(view, view.copy(source = WeatherSource.SILURIAN)))
        assertTrue(ForecastHistoryReload.showsLoading(view, view.copy(lat = 40.0)))
        assertTrue(ForecastHistoryReload.showsLoading(view, view.copy(lon = -100.0)))
    }
}
