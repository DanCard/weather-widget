package com.weatherwidget.data.model

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ForecastPartsTest {
    @Test
    fun `daily due and hourly due combine into the fetch`() {
        assertEquals(ForecastParts.BOTH, ForecastParts.of(daily = true, hourly = true))
        assertEquals(ForecastParts.DAILY, ForecastParts.of(daily = true, hourly = false))
        assertEquals(ForecastParts.HOURLY, ForecastParts.of(daily = false, hourly = true))
        assertNull(ForecastParts.of(daily = false, hourly = false))
    }

    @Test
    fun `only sources whose request can drop a part split`() {
        listOf(WeatherSource.GOOGLE_WEATHER, WeatherSource.NWS, WeatherSource.SILURIAN, WeatherSource.OPEN_METEO, WeatherSource.TOMORROW_IO)
            .forEach { assertTrue(it.id, ForecastParts.splits(it)) }
        listOf(WeatherSource.WEATHER_API, WeatherSource.OPEN_WEATHER_MAP)
            .forEach { assertFalse(it.id, ForecastParts.splits(it)) }
    }

    @Test
    fun `a non-splitting source requests everything`() {
        assertEquals(ForecastParts.BOTH, ForecastParts.requested(WeatherSource.WEATHER_API, ForecastParts.HOURLY))
        assertEquals(ForecastParts.BOTH, ForecastParts.requested(WeatherSource.OPEN_WEATHER_MAP, ForecastParts.DAILY))
        assertEquals(ForecastParts.DAILY, ForecastParts.requested(WeatherSource.OPEN_METEO, ForecastParts.DAILY))
    }

    private val none = emptySet<ForecastProduct>()
    private val hoursBlocked = setOf(ForecastProduct.HOURLY)
    private val daysBlocked = setOf(ForecastProduct.DAILY)

    @Test
    fun `a fetch takes both parts, less any product a quota refuses`() {
        assertEquals(ForecastParts.BOTH, ForecastParts.unblocked(none))
        // 2026-10-11 21:36: forecast/hours refused → the fetch is daily only, never a billed hours retry.
        assertEquals(ForecastParts.DAILY, ForecastParts.unblocked(hoursBlocked))
        assertEquals(ForecastParts.HOURLY, ForecastParts.unblocked(daysBlocked))
        assertNull("both refused: nothing to fetch", ForecastParts.unblocked(daysBlocked + hoursBlocked))
    }

    @Test
    fun `plan log line names each source's parts and horizon decision`() {
        assertEquals(
            "parts=NWS:BOTH,GOOGLE_WEATHER:DAILY horizons=NWS:SHORT(x),GOOGLE_WEATHER:LONG(y)",
            ForecastParts.planLogLine(
                linkedMapOf("NWS" to ForecastParts.BOTH, "GOOGLE_WEATHER" to ForecastParts.DAILY),
                listOf("NWS:SHORT(x)", "GOOGLE_WEATHER:LONG(y)"),
            ),
        )
        assertEquals("parts=none horizons=unjudged", ForecastParts.planLogLine(emptyMap(), emptyList()))
    }
}
