package com.weatherwidget.stats

import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Category(ShortDuration::class)
class RainAccuracyCalculatorTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun epochOf(dateTime: LocalDateTime): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    private fun historyRow(
        dateTime: LocalDateTime,
        timestampToGroupPredictions: Long,
        precipAmountMm: Float?,
        source: String = WeatherSource.NWS.id,
    ) = HourlyForecastHistoryEntity(
        dateTime = epochOf(dateTime),
        locationLat = 37.0,
        locationLon = -122.0,
        temperature = 60f,
        condition = "Rain",
        source = source,
        timestampToGroupPredictions = timestampToGroupPredictions,
        precipAmountMm = precipAmountMm,
        fetchedAt = timestampToGroupPredictions,
    )

    private fun observation(
        dateTime: LocalDateTime,
        precipAmountMm: Float?,
        api: String = WeatherSource.NWS.id,
        stationId: String = "KSFO",
    ) = ObservationEntity(
        stationId = stationId,
        stationName = stationId,
        timestamp = epochOf(dateTime),
        temperature = 60f,
        condition = "Rain",
        locationLat = 37.0,
        locationLon = -122.0,
        api = api,
        precipAmountMm = precipAmountMm,
    )

    private val day = LocalDate.of(2026, 5, 20)

    @Test
    fun `bucketDayNight splits at 8a and 8p clock boundaries`() {
        val amounts = mapOf(
            day.atTime(7, 0) to 1f, // night (before 8a)
            day.atTime(8, 0) to 2f, // day
            day.atTime(19, 0) to 3f, // day
            day.atTime(20, 0) to 4f, // night (8p onward)
        )
        val (dayMm, nightMm) = RainAccuracyCalculator.bucketDayNight(amounts)
        assertEquals(5f, dayMm) // 2 + 3
        assertEquals(5f, nightMm) // 1 + 4
    }

    @Test
    fun `bucketDayNight returns null for buckets with no hours`() {
        val dayOnly = mapOf(day.atTime(10, 0) to 2f)
        val (dayMm, nightMm) = RainAccuracyCalculator.bucketDayNight(dayOnly)
        assertEquals(2f, dayMm)
        assertNull("No night hours -> null, not zero", nightMm)
    }

    @Test
    fun `latestSnapshotPrecipByHour keeps the most recent snapshot per hour`() {
        val hour = day.atTime(10, 0)
        val rows = listOf(
            historyRow(hour, timestampToGroupPredictions = 100, precipAmountMm = 1f),
            historyRow(hour, timestampToGroupPredictions = 200, precipAmountMm = 4f), // newer -> wins
        )
        val byHour = RainAccuracyCalculator.latestSnapshotPrecipByHour(rows, zone)
        assertEquals(mapOf(hour to 4f), byHour)
    }

    @Test
    fun `actualPrecipByHour excludes NWS_BLEND and sums per hour`() {
        val hour = day.atTime(9, 0)
        val observations = listOf(
            observation(hour, precipAmountMm = 1f, stationId = "KSFO"),
            observation(hour.plusMinutes(30), precipAmountMm = 2f, stationId = "KSFO"),
            observation(hour, precipAmountMm = 9f, stationId = "NWS_BLEND"), // excluded
        )
        val byHour = RainAccuracyCalculator.actualPrecipByHour(observations, WeatherSource.NWS, zone)
        assertEquals(mapOf(hour to 3f), byHour)
    }

    @Test
    fun `actualPrecipByHour for non-NWS source keeps only MAIN rows`() {
        val hour = day.atTime(9, 0)
        val observations = listOf(
            observation(hour, precipAmountMm = 5f, api = WeatherSource.OPEN_METEO.id, stationId = "OPEN_METEO_MAIN"),
            observation(hour, precipAmountMm = 7f, api = WeatherSource.OPEN_METEO.id, stationId = "OPEN_METEO_ALT"),
        )
        val byHour = RainAccuracyCalculator.actualPrecipByHour(observations, WeatherSource.OPEN_METEO, zone)
        assertEquals(mapOf(hour to 5f), byHour)
    }
}
