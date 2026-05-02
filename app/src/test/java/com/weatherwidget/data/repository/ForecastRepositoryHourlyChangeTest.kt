package com.weatherwidget.data.repository

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
class ForecastRepositoryHourlyChangeTest {

    @Test
    fun `hasMeaningfulHourlyChange returns true when cloud cover changes`() {
        val existing = hourly(cloudCover = null)
        val fetched = hourly(cloudCover = 42)

        assertTrue(ForecastRepository.hasMeaningfulHourlyChange(existing, fetched))
    }

    @Test
    fun `hasMeaningfulHourlyChange returns true when precip probability changes`() {
        val existing = hourly(precipProbability = 10)
        val fetched = hourly(precipProbability = 40)

        assertTrue(ForecastRepository.hasMeaningfulHourlyChange(existing, fetched))
    }

    @Test
    fun `hasMeaningfulHourlyChange returns false when hourly row is unchanged`() {
        val existing = hourly(cloudCover = 55, precipProbability = 20)
        val fetched = hourly(cloudCover = 55, precipProbability = 20)

        assertFalse(ForecastRepository.hasMeaningfulHourlyChange(existing, fetched))
    }

    @Test
    fun `hasMeaningfulHourlyChange returns true when fetchedAt is more than one hour newer`() {
        val existing = hourly(cloudCover = 55, precipProbability = 20, fetchedAt = 1000L)
        val fetched = hourly(cloudCover = 55, precipProbability = 20, fetchedAt = 1000L + 61 * 60 * 1000L)

        assertTrue(ForecastRepository.hasMeaningfulHourlyChange(existing, fetched))
    }

    @Test
    fun `hasMeaningfulHourlyChange returns false when fetchedAt is less than one hour newer`() {
        val existing = hourly(cloudCover = 55, precipProbability = 20, fetchedAt = 1000L)
        val fetched = hourly(cloudCover = 55, precipProbability = 20, fetchedAt = 1000L + 30 * 60 * 1000L)

        assertFalse(ForecastRepository.hasMeaningfulHourlyChange(existing, fetched))
    }

    @Test
    fun `mergePreservingNullableFields keeps existing cloudCover when fetched is null`() {
        val existing = hourly(cloudCover = 42, precipProbability = 30)
        val fetched = hourly(cloudCover = null, precipProbability = 30)

        val merged = ForecastRepository.mergePreservingNullableFields(existing, fetched)

        assertEquals(42, merged.cloudCover)
    }

    @Test
    fun `mergePreservingNullableFields keeps existing precipProbability when fetched is null`() {
        val existing = hourly(cloudCover = 50, precipProbability = 70)
        val fetched = hourly(cloudCover = 50, precipProbability = null)

        val merged = ForecastRepository.mergePreservingNullableFields(existing, fetched)

        assertEquals(70, merged.precipProbability)
    }

    @Test
    fun `mergePreservingNullableFields prefers fetched value when not null`() {
        val existing = hourly(cloudCover = 42, precipProbability = 10)
        val fetched = hourly(cloudCover = 88, precipProbability = 90)

        val merged = ForecastRepository.mergePreservingNullableFields(existing, fetched)

        assertEquals(88, merged.cloudCover)
        assertEquals(90, merged.precipProbability)
    }

    @Test
    fun `mergePreservingNullableFields returns fetched as-is when no existing row`() {
        val fetched = hourly(cloudCover = null, precipProbability = null)

        val merged = ForecastRepository.mergePreservingNullableFields(existing = null, newlyFetched = fetched)

        assertEquals(fetched, merged)
    }

    private fun hourly(
        precipProbability: Int? = 20,
        cloudCover: Int? = 55,
        fetchedAt: Long = 1L,
    ) = HourlyForecastEntity(
        dateTime = TestData.toEpoch("2026-03-14T21:00"),
        locationLat = 37.42,
        locationLon = -122.08,
        temperature = 60f,
        condition = "Mostly Clear",
        source = WeatherSource.NWS.id,
        precipProbability = precipProbability,
        cloudCover = cloudCover,
        fetchedAt = fetchedAt,
    )
}
