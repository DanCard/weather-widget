package com.weatherwidget.desktop

import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.toSnapshot
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class CurrentStatusResolverTest {

    @Test
    fun `resolve packages the resolved temp with identity and forecast metadata`() {
        val resolver = CurrentStatusResolver(
            latitude = 37.4220,
            longitude = -122.0841,
            source = "NWS",
        ) { _, _ ->
            DesktopWeatherRepository.ResolvedCurrentTemp(
                displayTemp = 66.79f,
                appliedDelta = 4.08f,
                deltaFromYesterday = 1.3f,
            )
        }

        val forecast = ForecastResult(
            currentObservedAt = 1_780_000_000_000L,
            currentCondition = "Clear",
        ).toSnapshot()
        val status = resolver.resolve(forecast, now = 1_780_000_000_500L)

        assertEquals(37.4220, status.locationLat, 0.0)
        assertEquals(-122.0841, status.locationLon, 0.0)
        assertEquals("NWS", status.source)
        assertEquals(66.79f, status.displayTempF!!, 0.001f)
        assertEquals(4.08f, status.appliedDeltaF!!, 0.001f)
        assertEquals(1.3f, status.deltaFromYesterdayF!!, 0.001f)
        assertEquals(1_780_000_000_000L, status.observedAtMs)
        assertEquals("Clear", status.condition)
        assertEquals(1_780_000_000_500L, status.updatedAt)
    }

    @Test
    fun `resolve passes nulls through when nothing is resolvable`() {
        val resolver = CurrentStatusResolver(1.0, 2.0, "NWS") { _, _ ->
            DesktopWeatherRepository.ResolvedCurrentTemp(null, null, null)
        }

        val status = resolver.resolve(ForecastResult().toSnapshot(), now = 42L)

        assertNull(status.displayTempF)
        assertNull(status.appliedDeltaF)
        assertNull(status.deltaFromYesterdayF)
        assertEquals(42L, status.updatedAt)
    }
}
