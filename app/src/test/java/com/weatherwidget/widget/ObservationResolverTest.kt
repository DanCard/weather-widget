package com.weatherwidget.widget

import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ObservationResolverTest {

    @Test
    fun `resolveObservedCurrentTemp picks newest observation for active source`() {
        val today = "2026-02-26"
        val weatherList =
            listOf(
                weather(
                    date = today,
                    source = WeatherSource.NWS.id,
                    currentTemp = 53.0f,
                    fetchedAt = 1_000L,
                    observedAt = 900L,
                ),
                weather(
                    date = today,
                    source = WeatherSource.NWS.id,
                    currentTemp = 54.0f,
                    fetchedAt = 2_000L,
                    observedAt = 1_800L,
                ),
                weather(
                    date = today,
                    source = WeatherSource.OPEN_METEO.id,
                    currentTemp = 60.0f,
                    fetchedAt = 3_000L,
                    observedAt = 3_000L,
                ),
            )

        val resolved =
            ObservationResolver.resolveObservedCurrentTemp(
                weatherList = weatherList,
                displaySource = WeatherSource.NWS,
                todayStr = today,
            )

        assertNotNull(resolved)
        assertEquals(54.0f, resolved!!.temperature)
        assertEquals(1_800L, resolved.observedAt)
        assertEquals(WeatherSource.NWS.id, resolved.source)
        assertEquals(2_000L, resolved.rowFetchedAt)
    }

    @Test
    fun `resolveObservedCurrentTemp falls back to fetchedAt when observedAt is null`() {
        val today = "2026-02-26"
        val weatherList =
            listOf(
                weather(
                    date = today,
                    source = WeatherSource.NWS.id,
                    currentTemp = 51.0f,
                    fetchedAt = 7_000L,
                    observedAt = null,
                ),
            )

        val resolved =
            ObservationResolver.resolveObservedCurrentTemp(
                weatherList = weatherList,
                displaySource = WeatherSource.NWS,
                todayStr = today,
            )

        assertNotNull(resolved)
        assertEquals(51.0f, resolved!!.temperature)
        assertEquals(7_000L, resolved.observedAt)
        assertEquals(WeatherSource.NWS.id, resolved.source)
        assertEquals(7_000L, resolved.rowFetchedAt)
    }

    @Test
    fun `resolveObservedCurrentTemp returns null when active source has no current temp`() {
        val today = "2026-02-26"
        val weatherList =
            listOf(
                weather(
                    date = today,
                    source = WeatherSource.OPEN_METEO.id,
                    currentTemp = 60.0f,
                    fetchedAt = 3_000L,
                    observedAt = 3_000L,
                ),
                weather(
                    date = today,
                    source = WeatherSource.NWS.id,
                    currentTemp = null,
                    fetchedAt = 2_000L,
                    observedAt = 2_000L,
                ),
            )

        val resolved =
            ObservationResolver.resolveObservedCurrentTemp(
                weatherList = weatherList,
                displaySource = WeatherSource.NWS,
                todayStr = today,
            )

        assertNull(resolved)
    }

    private fun weather(
        date: String,
        source: String,
        currentTemp: Float?,
        fetchedAt: Long,
        observedAt: Long?,
    ): WeatherEntity {
        return WeatherEntity(
            date = date,
            locationLat = 37.42,
            locationLon = -122.08,
            locationName = "Mountain View",
            highTemp = 70.0f,
            lowTemp = 50.0f,
            currentTemp = currentTemp,
            condition = "Clear",
            isActual = false,
            source = source,
            fetchedAt = fetchedAt,
            currentTempObservedAt = observedAt,
        )
    }
}
