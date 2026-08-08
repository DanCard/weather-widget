package com.weatherwidget.shared.stats

import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ActualsBaselineResolverTest {

    private val all: (WeatherSource) -> Boolean = { true }
    private val none: (WeatherSource) -> Boolean = { false }

    @Test
    fun `source with its own actuals is graded against itself`() {
        val result = ActualsBaselineResolver.resolveBaselineSource(
            gradedSource = WeatherSource.NWS,
            orderedVisibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO),
            hasRowForDate = all,
        )

        assertEquals(WeatherSource.NWS, result)
    }

    @Test
    fun `forecast-only source falls back instead of grading against its own forecast`() {
        // Visual Crossing is HistoricalDataKind.NONE: its computedHighTemp blends only its own
        // VISUAL_CROSSING_MAIN backfill rows, i.e. its own forecast. Grading it against itself is
        // the circularity this resolver exists to prevent.
        val result = ActualsBaselineResolver.resolveBaselineSource(
            gradedSource = WeatherSource.VISUAL_CROSSING,
            orderedVisibleSources = listOf(WeatherSource.VISUAL_CROSSING, WeatherSource.NWS),
            hasRowForDate = all,
        )

        assertEquals(WeatherSource.NWS, result)
    }

    /**
     * Pins the 2026-08-08 decision to rank by data quality rather than by the user's display order.
     * Swapping the comparator in [ActualsBaselineResolver] to plain priority order must fail here.
     */
    @Test
    fun stationObservationOutranksEarlierProviderHistory() {
        val result = ActualsBaselineResolver.resolveBaselineSource(
            gradedSource = WeatherSource.OPEN_WEATHER_MAP,
            // WeatherAPI is the user's primary and sits first; NWS is last.
            orderedVisibleSources = listOf(
                WeatherSource.WEATHER_API,
                WeatherSource.OPEN_WEATHER_MAP,
                WeatherSource.NWS,
            ),
            hasRowForDate = all,
        )

        assertEquals(
            "STATION_OBSERVATION must outrank ARCHIVED_PROVIDER_HISTORY regardless of display order",
            WeatherSource.NWS,
            result,
        )
    }

    @Test
    fun `reanalysis archive outranks provider history`() {
        val result = ActualsBaselineResolver.resolveBaselineSource(
            gradedSource = WeatherSource.VISUAL_CROSSING,
            orderedVisibleSources = listOf(
                WeatherSource.VISUAL_CROSSING,
                WeatherSource.SILURIAN,
                WeatherSource.OPEN_METEO,
            ),
            hasRowForDate = all,
        )

        assertEquals(WeatherSource.OPEN_METEO, result)
    }

    @Test
    fun `display order breaks ties within one kind`() {
        // Silurian and WeatherAPI are both ARCHIVED_PROVIDER_HISTORY.
        val silurianFirst = ActualsBaselineResolver.resolveBaselineSource(
            gradedSource = WeatherSource.VISUAL_CROSSING,
            orderedVisibleSources = listOf(
                WeatherSource.VISUAL_CROSSING,
                WeatherSource.SILURIAN,
                WeatherSource.WEATHER_API,
            ),
            hasRowForDate = all,
        )
        val weatherApiFirst = ActualsBaselineResolver.resolveBaselineSource(
            gradedSource = WeatherSource.VISUAL_CROSSING,
            orderedVisibleSources = listOf(
                WeatherSource.VISUAL_CROSSING,
                WeatherSource.WEATHER_API,
                WeatherSource.SILURIAN,
            ),
            hasRowForDate = all,
        )

        assertEquals(WeatherSource.SILURIAN, silurianFirst)
        assertEquals(WeatherSource.WEATHER_API, weatherApiFirst)
    }

    @Test
    fun `candidate without a stored row for the date is skipped`() {
        val result = ActualsBaselineResolver.resolveBaselineSource(
            gradedSource = WeatherSource.VISUAL_CROSSING,
            orderedVisibleSources = listOf(
                WeatherSource.VISUAL_CROSSING,
                WeatherSource.NWS,
                WeatherSource.OPEN_METEO,
            ),
            hasRowForDate = { it != WeatherSource.NWS },
        )

        assertEquals(WeatherSource.OPEN_METEO, result)
    }

    @Test
    fun `source with native actuals but no row for the date falls back to another source`() {
        val result = ActualsBaselineResolver.resolveBaselineSource(
            gradedSource = WeatherSource.NWS,
            orderedVisibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO),
            hasRowForDate = { it == WeatherSource.OPEN_METEO },
        )

        assertEquals(WeatherSource.OPEN_METEO, result)
    }

    @Test
    fun `no qualifying candidate yields null so the day is excluded`() {
        val onlyForecastOnlySources = ActualsBaselineResolver.resolveBaselineSource(
            gradedSource = WeatherSource.VISUAL_CROSSING,
            orderedVisibleSources = listOf(
                WeatherSource.VISUAL_CROSSING,
                WeatherSource.OPEN_WEATHER_MAP,
            ),
            hasRowForDate = all,
        )
        val noRowsAtAll = ActualsBaselineResolver.resolveBaselineSource(
            gradedSource = WeatherSource.VISUAL_CROSSING,
            orderedVisibleSources = listOf(WeatherSource.VISUAL_CROSSING, WeatherSource.NWS),
            hasRowForDate = none,
        )

        assertNull(onlyForecastOnlySources)
        assertNull(noRowsAtAll)
    }

    @Test
    fun `hasNativeActuals matches the declared HistoricalDataKind`() {
        assertTrue(ActualsBaselineResolver.hasNativeActuals(WeatherSource.NWS))
        assertTrue(ActualsBaselineResolver.hasNativeActuals(WeatherSource.OPEN_METEO))
        assertTrue(ActualsBaselineResolver.hasNativeActuals(WeatherSource.SILURIAN))
        assertTrue(ActualsBaselineResolver.hasNativeActuals(WeatherSource.WEATHER_API))
        assertTrue(ActualsBaselineResolver.hasNativeActuals(WeatherSource.TOMORROW_IO))
        assertEquals(false, ActualsBaselineResolver.hasNativeActuals(WeatherSource.VISUAL_CROSSING))
        assertEquals(false, ActualsBaselineResolver.hasNativeActuals(WeatherSource.OPEN_WEATHER_MAP))
    }
}
