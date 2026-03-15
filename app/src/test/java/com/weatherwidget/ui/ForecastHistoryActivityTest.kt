package com.weatherwidget.ui

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.ForecastHistoryActivity.ActualLookupMode
import com.weatherwidget.ui.ForecastHistoryActivity.ButtonMode
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.buildActualFromNwsObservations
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.hasRequiredHistoryExtras
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.isNwsObservationStation
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.normalizeSource
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.resolveActualLookupMode
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.resolveButtonMode
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.selectLatestCompleteActualFromForecasts
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.shouldLaunchTemperature
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.shouldShowTemperatureButton
import com.weatherwidget.ui.ForecastHistoryActivity.GraphMode

class ForecastHistoryActivityTest {

    // --- shouldLaunchTemperature ---

    @Test
    fun `shouldLaunchTemperature - no date - returns false`() {
        assertFalse(shouldLaunchTemperature(hasDate = false, showTemperatureButton = true))
    }

    @Test
    fun `shouldLaunchTemperature - date present and hourly button active - returns true`() {
        assertTrue(shouldLaunchTemperature(hasDate = true, showTemperatureButton = true))
    }

    @Test
    fun `shouldLaunchTemperature - date present and hourly button inactive - returns false`() {
        assertFalse(shouldLaunchTemperature(hasDate = true, showTemperatureButton = false))
    }

    // --- shouldShowTemperatureButton ---

    @Test
    fun `shouldShowTemperatureButton - today without actuals - returns true`() {
        val today = LocalDate.of(2026, 2, 25)
        assertTrue(shouldShowTemperatureButton(date = today, hasActualValues = false, today = today))
    }

    @Test
    fun `shouldShowTemperatureButton - future without actuals - returns true`() {
        val today = LocalDate.of(2026, 2, 25)
        val future = today.plusDays(2)
        assertTrue(shouldShowTemperatureButton(date = future, hasActualValues = false, today = today))
    }

    @Test
    fun `shouldShowTemperatureButton - today with actuals - returns false`() {
        val today = LocalDate.of(2026, 2, 25)
        assertFalse(shouldShowTemperatureButton(date = today, hasActualValues = true, today = today))
    }

    @Test
    fun `shouldShowTemperatureButton - past without actuals - returns false`() {
        val today = LocalDate.of(2026, 2, 25)
        val past = today.minusDays(1)
        assertFalse(shouldShowTemperatureButton(date = past, hasActualValues = false, today = today))
    }

    // --- resolveButtonMode ---

    @Test
    fun `resolveButtonMode - hourly button active - returns TEMPERATURE regardless of graphMode`() {
        assertEquals(ButtonMode.TEMPERATURE, resolveButtonMode(showTemperatureButton = true, GraphMode.EVOLUTION))
        assertEquals(ButtonMode.TEMPERATURE, resolveButtonMode(showTemperatureButton = true, GraphMode.ERROR))
    }

    @Test
    fun `resolveButtonMode - hourly button inactive and evolution mode - returns EVOLUTION`() {
        assertEquals(ButtonMode.EVOLUTION, resolveButtonMode(showTemperatureButton = false, GraphMode.EVOLUTION))
    }

    @Test
    fun `resolveButtonMode - hourly button inactive and error mode - returns ERROR`() {
        assertEquals(ButtonMode.ERROR, resolveButtonMode(showTemperatureButton = false, GraphMode.ERROR))
    }

    // --- hasRequiredHistoryExtras ---

    @Test
    fun `hasRequiredHistoryExtras - accepts date with zero coordinates extras present`() {
        assertTrue(
            hasRequiredHistoryExtras(
                targetDate = "2026-02-25",
                hasLatExtra = true,
                hasLonExtra = true,
            ),
        )
    }

    @Test
    fun `hasRequiredHistoryExtras - missing lat extra returns false`() {
        assertFalse(
            hasRequiredHistoryExtras(
                targetDate = "2026-02-25",
                hasLatExtra = false,
                hasLonExtra = true,
            ),
        )
    }

    @Test
    fun `hasRequiredHistoryExtras - missing lon extra returns false`() {
        assertFalse(
            hasRequiredHistoryExtras(
                targetDate = "2026-02-25",
                hasLatExtra = true,
                hasLonExtra = false,
            ),
        )
    }

    @Test
    fun `hasRequiredHistoryExtras - missing date returns false`() {
        assertFalse(
            hasRequiredHistoryExtras(
                targetDate = null,
                hasLatExtra = true,
                hasLonExtra = true,
            ),
        )
    }

    // --- normalizeSource ---

    @Test
    fun `normalizeSource - maps known history source labels`() {
        assertEquals(WeatherSource.NWS, normalizeSource("NWS"))
        assertEquals(WeatherSource.OPEN_METEO, normalizeSource("Open-Meteo"))
        assertEquals(WeatherSource.OPEN_METEO, normalizeSource("OPEN_METEO"))
        assertEquals(WeatherSource.WEATHER_API, normalizeSource("WeatherAPI"))
        assertEquals(WeatherSource.WEATHER_API, normalizeSource("WEATHER_API"))
        assertEquals(WeatherSource.SILURIAN, normalizeSource("Silurian"))
        assertEquals(WeatherSource.SILURIAN, normalizeSource("SILURIAN"))
    }

    @Test
    fun `normalizeSource - returns null for unknown label`() {
        assertNull(normalizeSource("Unknown API"))
        assertNull(normalizeSource(null))
    }

    // --- resolveActualLookupMode ---

    @Test
    fun `resolveActualLookupMode - past date with requested source uses source specific lookup`() {
        val today = LocalDate.of(2026, 2, 25)
        val past = today.minusDays(1)

        assertEquals(
            ActualLookupMode.SOURCE_SPECIFIC,
            resolveActualLookupMode(
                date = past,
                requestedSource = WeatherSource.NWS,
                today = today,
            ),
        )
    }

    @Test
    fun `resolveActualLookupMode - past date without requested source uses any source lookup`() {
        val today = LocalDate.of(2026, 2, 25)
        val past = today.minusDays(1)

        assertEquals(
            ActualLookupMode.ANY_SOURCE,
            resolveActualLookupMode(
                date = past,
                requestedSource = null,
                today = today,
            ),
        )
    }

    @Test
    fun `resolveActualLookupMode - today never loads actuals`() {
        val today = LocalDate.of(2026, 2, 25)

        assertEquals(
            ActualLookupMode.NONE,
            resolveActualLookupMode(
                date = today,
                requestedSource = WeatherSource.OPEN_METEO,
                today = today,
            ),
        )
    }

    @Test
    fun `resolveActualLookupMode - future never loads actuals`() {
        val today = LocalDate.of(2026, 2, 25)
        val future = today.plusDays(1)

        assertEquals(
            ActualLookupMode.NONE,
            resolveActualLookupMode(
                date = future,
                requestedSource = WeatherSource.OPEN_METEO,
                today = today,
            ),
        )
    }

    @Test
    fun `selectLatestCompleteActualFromForecasts - prefers most recently fetched complete row`() {
        val targetDate = "2026-02-28"
        val base = ForecastEntity(
            targetDate = targetDate,
            forecastDate = "2026-02-28",
            locationLat = 37.0,
            locationLon = -122.0,
            locationName = "",
            highTemp = 77f,
            lowTemp = 56f,
            condition = "Sunny",
            source = WeatherSource.NWS.id,
            fetchedAt = 1000L,
        )
        val newerComplete = base.copy(highTemp = 78f, lowTemp = 57f, fetchedAt = 3000L)
        val newestPartial = base.copy(highTemp = 79f, lowTemp = null, fetchedAt = 4000L)

        val selected = selectLatestCompleteActualFromForecasts(listOf(base, newerComplete, newestPartial))

        assertEquals(78f, selected?.highTemp)
        assertEquals(57f, selected?.lowTemp)
        assertEquals(3000L, selected?.fetchedAt)
    }

    @Test
    fun `isNwsObservationStation - rejects non NWS synthetic station ids`() {
        assertTrue(isNwsObservationStation("KSJC"))
        assertTrue(isNwsObservationStation("AW020"))
        assertFalse(isNwsObservationStation("OPEN_METEO_2"))
        assertFalse(isNwsObservationStation("WEATHER_API_MAIN"))
    }

    @Test
    fun `buildActualFromNwsObservations - uses only NWS rows for high and low`() {
        val targetDate = "2026-02-28"
        val observations = listOf(
            ObservationEntity(
                stationId = "KSJC",
                stationName = "San Jose",
                timestamp = 1L,
                temperature = 77f,
                condition = "Sunny",
                locationLat = 37.0,
                locationLon = -122.0,
                fetchedAt = 100L,
            ),
            ObservationEntity(
                stationId = "AW020",
                stationName = "Mountain View",
                timestamp = 2L,
                temperature = 55f,
                condition = "Sunny",
                locationLat = 37.0,
                locationLon = -122.0,
                fetchedAt = 200L,
            ),
            ObservationEntity(
                stationId = "OPEN_METEO_2",
                stationName = "Meteo South",
                timestamp = 3L,
                temperature = 80.9f,
                condition = "Sunny",
                locationLat = 37.0,
                locationLon = -122.0,
                fetchedAt = 300L,
            ),
        )

        val actual = buildActualFromNwsObservations(targetDate, 37.0, -122.0, observations)

        assertNotNull(actual)
        assertEquals(WeatherSource.NWS.id, actual?.source)
        assertEquals(77f, actual?.highTemp)
        assertEquals(55f, actual?.lowTemp)
    }

    @Test
    fun `buildActualFromNwsObservations - returns null when only non NWS rows exist`() {
        val targetDate = "2026-02-28"
        val observations = listOf(
            ObservationEntity(
                stationId = "OPEN_METEO_2",
                stationName = "Meteo South",
                timestamp = 1L,
                temperature = 80.9f,
                condition = "Sunny",
                locationLat = 37.0,
                locationLon = -122.0,
            ),
            ObservationEntity(
                stationId = "WEATHER_API_MAIN",
                stationName = "WAPI Current",
                timestamp = 2L,
                temperature = 79.1f,
                condition = "Sunny",
                locationLat = 37.0,
                locationLon = -122.0,
            ),
        )

        val actual = buildActualFromNwsObservations(targetDate, 37.0, -122.0, observations)

        assertNull(actual)
    }
}
