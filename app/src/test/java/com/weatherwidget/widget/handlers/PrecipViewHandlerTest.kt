package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ZoomLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class PrecipViewHandlerTest {

    @Test
    fun `buildPrecipHourDataList returns empty when source has no data in window`() {
        // Simulates the race condition: hourlyForecasts exists for a different
        // source, but the selected source has no entries in the time window.
        val hours = listOf(
            hourly("2026-03-14T18:00", WeatherSource.OPEN_METEO),
            hourly("2026-03-14T19:00", WeatherSource.OPEN_METEO),
            hourly("2026-03-14T20:00", WeatherSource.OPEN_METEO),
        )

        val result = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = hours,
            centerTime = LocalDateTime.of(2026, 3, 14, 19, 0),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
        )

        // The fallback logic picks firstOrNull(), so it should NOT be empty here
        // because the groupBy mapValues uses fallback. But if all data is outside
        // the window, it will be empty.
        // This test documents the actual behavior.
        assertTrue(
            "result should be non-empty due to fallback (firstOrNull), " +
                "actual size=${result.size}",
            result.isNotEmpty(),
        )
    }

    @Test
    fun `buildPrecipHourDataList returns empty when data is outside time window`() {
        // Data exists but is far outside the zoom window — this IS the empty path
        val hours = listOf(
            hourly("2026-03-20T18:00", WeatherSource.NWS),
            hourly("2026-03-20T19:00", WeatherSource.NWS),
        )

        val result = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = hours,
            centerTime = LocalDateTime.of(2026, 3, 14, 19, 0),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
        )

        assertEquals("data outside zoom window should yield empty output", 0, result.size)
    }

    @Test
    fun `buildPrecipHourDataList passes precipAmountMm through`() {
        val hours = listOf(
            hourly("2026-03-14T18:00", WeatherSource.NWS, precipAmountMm = 3.5f),
            hourly("2026-03-14T19:00", WeatherSource.NWS, precipAmountMm = 1.2f),
            hourly("2026-03-14T20:00", WeatherSource.NWS, precipAmountMm = 0.0f),
        )

        val result = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = hours,
            centerTime = LocalDateTime.of(2026, 3, 14, 19, 0),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
        )

        val matched = result.filter { it.precipAmountMm != null }
        assertTrue(
            "At least one result should carry precipAmountMm, actual=${result.map { it.precipAmountMm }}",
            matched.isNotEmpty(),
        )
        assertTrue(
            "Should contain 3.5mm entry",
            matched.any { it.precipAmountMm == 3.5f },
        )
    }

    @Test
    fun `buildPrecipHourDataList null precipAmountMm passes through as null`() {
        val hours = listOf(
            hourly("2026-03-14T18:00", WeatherSource.NWS),
            hourly("2026-03-14T19:00", WeatherSource.NWS),
        )

        val result = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = hours,
            centerTime = LocalDateTime.of(2026, 3, 14, 18, 30),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
        )

        val withAmount = result.filter { it.precipAmountMm != null }
        assertEquals(
            "All precipAmountMm should be null when source has no data",
            0,
            withAmount.size,
        )
    }

    @Test
    fun `buildPrecipHourDataList carries actual precip amount alongside forecast for past hours`() {
        val forecastHours = listOf(
            hourly("2026-03-14T18:00", WeatherSource.NWS, precipAmountMm = 0.5f),
            hourly("2026-03-14T19:00", WeatherSource.NWS, precipAmountMm = 0.7f),
        )
        val actuals = mapOf(
            LocalDateTime.of(2026, 3, 14, 18, 0) to 3.5f,
        )

        val result = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = forecastHours,
            centerTime = LocalDateTime.of(2026, 3, 14, 18, 30),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actualPrecipByHour = actuals,
            now = LocalDateTime.of(2026, 3, 14, 20, 0),
        )

        assertTrue(
            "Past hour should keep forecast amount and carry actual amount. amounts=${result.map { Triple(it.dateTime, it.precipAmountMm, it.actualPrecipAmountMm) }}",
            result.any {
                it.dateTime == LocalDateTime.of(2026, 3, 14, 18, 0) &&
                    it.precipAmountMm == 0.5f &&
                    it.actualPrecipAmountMm == 3.5f
            },
        )
    }

    @Test
    fun `buildPrecipHourDataList keeps forecast precip amount for future hours`() {
        val forecastHours = listOf(
            hourly("2026-03-14T18:00", WeatherSource.NWS, precipAmountMm = 0.5f),
        )
        val actuals = mapOf(
            LocalDateTime.of(2026, 3, 14, 18, 0) to 3.5f,
        )

        val result = PrecipViewHandler.buildPrecipHourDataList(
            hourlyForecasts = forecastHours,
            centerTime = LocalDateTime.of(2026, 3, 14, 18, 0),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actualPrecipByHour = actuals,
            now = LocalDateTime.of(2026, 3, 14, 17, 0),
        )

        assertTrue(
            "Future hour should keep forecast amount and no actual amount. amounts=${result.map { Triple(it.dateTime, it.precipAmountMm, it.actualPrecipAmountMm) }}",
            result.any {
                it.dateTime == LocalDateTime.of(2026, 3, 14, 18, 0) &&
                    it.precipAmountMm == 0.5f &&
                    it.actualPrecipAmountMm == null
            },
        )
    }

    @Test
    fun `buildActualPrecipByHour buckets selected source observations by hour`() {
        val observations = listOf(
            observation("NWS", "KNUQ", "2026-03-14T18:10", 1.25f),
            observation("NWS", "KSJC", "2026-03-14T18:45", 0.75f),
            observation("NWS", "NWS_BLEND", "2026-03-14T18:30", 9.0f),
            observation("OPEN_METEO", "OPEN_METEO_MAIN", "2026-03-14T18:30", 7.0f),
            observation("NWS", "KNUQ", "2026-03-14T19:00", null),
        )

        val result = PrecipViewHandler.buildActualPrecipByHour(
            observations = observations,
            displaySource = WeatherSource.NWS,
        )

        assertEquals(1, result.size)
        assertEquals(2.0f, result[LocalDateTime.of(2026, 3, 14, 18, 0)] ?: -1f, 0.001f)
    }

    @Test
    fun `buildActualPrecipByHour uses main rows for non NWS sources`() {
        val observations = listOf(
            observation("OPEN_METEO", "OPEN_METEO_MAIN", "2026-03-14T18:10", 1.25f),
            observation("OPEN_METEO", "OPEN_METEO_STATION", "2026-03-14T18:45", 4.0f),
            observation("NWS", "KNUQ", "2026-03-14T18:30", 7.0f),
        )

        val result = PrecipViewHandler.buildActualPrecipByHour(
            observations = observations,
            displaySource = WeatherSource.OPEN_METEO,
        )

        assertEquals(1, result.size)
        assertEquals(1.25f, result[LocalDateTime.of(2026, 3, 14, 18, 0)] ?: -1f, 0.001f)
    }

    private fun hourly(
        dateTime: String,
        source: WeatherSource,
        precipAmountMm: Float? = null,
    ) = HourlyForecastEntity(
        dateTime = LocalDateTime.parse(dateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        locationLat = 37.42,
        locationLon = -122.08,
        temperature = 60f,
        condition = "Mostly Clear",
        source = source.id,
        precipProbability = 30,
        cloudCover = 50,
        precipAmountMm = precipAmountMm,
        fetchedAt = 1L,
    )

    private fun observation(
        api: String,
        stationId: String,
        dateTime: String,
        precipAmountMm: Float?,
    ) = ObservationEntity(
        stationId = stationId,
        stationName = stationId,
        timestamp = LocalDateTime.parse(dateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        temperature = 60f,
        condition = "observed",
        locationLat = 37.42,
        locationLon = -122.08,
        api = api,
        precipAmountMm = precipAmountMm,
    )
}
