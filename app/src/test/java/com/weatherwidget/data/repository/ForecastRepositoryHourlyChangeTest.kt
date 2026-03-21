package com.weatherwidget.data.repository

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun hourly(
        precipProbability: Int? = 20,
        cloudCover: Int? = 55,
        fetchedAt: Long = 1L,
    ) = HourlyForecastEntity(
        dateTime = "2026-03-14T21:00",
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
