package com.weatherwidget.widget

import com.weatherwidget.data.local.CurrentTempEntity
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ObservationResolverTest {

    @Test
    fun `resolveObservedCurrentTemp picks newest observation for active source`() {
        val currentTemps =
            listOf(
                currentTemp(
                    source = WeatherSource.NWS.id,
                    temperature = 53.0f,
                    fetchedAt = 1_000L,
                    observedAt = 900L,
                ),
                currentTemp(
                    source = WeatherSource.NWS.id,
                    temperature = 54.0f,
                    fetchedAt = 2_000L,
                    observedAt = 1_800L,
                ),
                currentTemp(
                    source = WeatherSource.OPEN_METEO.id,
                    temperature = 60.0f,
                    fetchedAt = 3_000L,
                    observedAt = 3_000L,
                ),
            )

        val resolved =
            ObservationResolver.resolveObservedCurrentTemp(
                currentTemps = currentTemps,
                displaySource = WeatherSource.NWS,
            )

        assertNotNull(resolved)
        assertEquals(54.0f, resolved!!.temperature)
        assertEquals(1_800L, resolved.observedAt)
        assertEquals(WeatherSource.NWS.id, resolved.source)
        assertEquals(2_000L, resolved.rowFetchedAt)
    }

    @Test
    fun `resolveObservedCurrentTemp uses observedAt for ordering`() {
        val currentTemps =
            listOf(
                currentTemp(
                    source = WeatherSource.NWS.id,
                    temperature = 51.0f,
                    fetchedAt = 7_000L,
                    observedAt = 5_000L,
                ),
            )

        val resolved =
            ObservationResolver.resolveObservedCurrentTemp(
                currentTemps = currentTemps,
                displaySource = WeatherSource.NWS,
            )

        assertNotNull(resolved)
        assertEquals(51.0f, resolved!!.temperature)
        assertEquals(5_000L, resolved.observedAt)
        assertEquals(WeatherSource.NWS.id, resolved.source)
        assertEquals(7_000L, resolved.rowFetchedAt)
    }

    @Test
    fun `resolveObservedCurrentTemp returns null when active source has no current temp`() {
        val currentTemps =
            listOf(
                currentTemp(
                    source = WeatherSource.OPEN_METEO.id,
                    temperature = 60.0f,
                    fetchedAt = 3_000L,
                    observedAt = 3_000L,
                ),
            )

        val resolved =
            ObservationResolver.resolveObservedCurrentTemp(
                currentTemps = currentTemps,
                displaySource = WeatherSource.NWS,
            )

        assertNull(resolved)
    }

    private fun currentTemp(
        source: String,
        temperature: Float,
        fetchedAt: Long,
        observedAt: Long,
    ): CurrentTempEntity {
        return CurrentTempEntity(
            date = "2026-02-26",
            source = source,
            locationLat = 37.42,
            locationLon = -122.08,
            temperature = temperature,
            observedAt = observedAt,
            condition = "Clear",
            fetchedAt = fetchedAt,
        )
    }
}
