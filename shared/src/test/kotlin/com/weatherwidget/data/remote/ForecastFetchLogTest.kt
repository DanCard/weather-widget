package com.weatherwidget.data.remote

import com.weatherwidget.shared.util.AppLog
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ForecastFetchLogTest {
    @After
    fun tearDown() = AppLog.resetToDefault()

    @Test
    fun `emits uniform FORECAST_FETCH success and failure rows via AppLog`() {
        val rows = mutableListOf<Triple<String, String, String>>()
        AppLog.install { tag, msg, level -> rows += Triple(tag, msg, level) }

        ForecastFetchLog.logSuccess("OPEN_METEO", 342L)
        ForecastFetchLog.logFailure("OPEN_METEO", 13_250L, "TIMEOUT", "HttpRequestTimeoutException")

        assertEquals(
            listOf(
                Triple("FORECAST_FETCH", "source=OPEN_METEO ok=true ms=342", "INFO"),
                Triple("FORECAST_FETCH", "source=OPEN_METEO ok=false ms=13250 code=TIMEOUT class=HttpRequestTimeoutException", "WARN"),
            ),
            rows,
        )
    }
}
