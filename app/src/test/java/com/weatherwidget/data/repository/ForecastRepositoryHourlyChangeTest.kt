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
    fun `hasMeaningfulHourlyChange returns false for an unchanged row no matter how old fetchedAt is`() {
        // There is no time-based force-write: an identical row is never rewritten just to refresh
        // fetchedAt. (fetchedAt = content-production time; freshness/staleness lives elsewhere.)
        val existing = hourly(cloudCover = 55, precipProbability = 20, fetchedAt = 1000L)
        val muchNewer = hourly(cloudCover = 55, precipProbability = 20, fetchedAt = 1000L + 100L * 24 * 60 * 60 * 1000L)

        assertFalse(ForecastRepository.hasMeaningfulHourlyChange(existing, muchNewer))
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

    @Test
    fun `siteExactExistingByDateTime ignores a fresher row from a different coordinate fragment`() {
        // 2026-07-10 Samsung bug: a GPS-jitter fragment absorbed the newest fetch (96% cloud),
        // then the change gate compared the next fetch at the display site against THAT row,
        // saw "no change", and never wrote — pinning the display site at the stale 67%.
        val displaySiteStale = hourly(cloudCover = 67, fetchedAt = 1_000L, lat = 37.417, lon = -122.089)
        val jitterFragmentFresh = hourly(cloudCover = 96, fetchedAt = 9_000L, lat = 37.422, lon = -122.087)

        val existing = ForecastRepository.siteExactExistingByDateTime(
            listOf(displaySiteStale, jitterFragmentFresh),
            lat = 37.417,
            lon = -122.089,
        )

        assertEquals(displaySiteStale, existing[displaySiteStale.dateTime])
        // The revised value must therefore register as a meaningful change and be written.
        val refetched = hourly(cloudCover = 96, fetchedAt = 10_000L, lat = 37.417, lon = -122.089)
        assertTrue(ForecastRepository.hasMeaningfulHourlyChange(existing[refetched.dateTime], refetched))
    }

    @Test
    fun `siteExactExistingByDateTime is empty when only other fragments have rows`() {
        // Brand-new site: nothing to diff against, so every incoming row must be written
        // (hasMeaningfulHourlyChange(null, x) == true) rather than being masked by neighbors.
        val jitterFragment = hourly(cloudCover = 96, lat = 37.422, lon = -122.087)

        val existing = ForecastRepository.siteExactExistingByDateTime(
            listOf(jitterFragment),
            lat = 37.417,
            lon = -122.089,
        )

        assertTrue(existing.isEmpty())
    }

    private fun hourly(
        precipProbability: Int? = 20,
        cloudCover: Int? = 55,
        fetchedAt: Long = 1L,
        lat: Double = 37.42,
        lon: Double = -122.08,
    ) = HourlyForecastEntity(
        dateTime = TestData.toEpoch("2026-03-14T21:00"),
        locationLat = lat,
        locationLon = lon,
        temperature = 60f,
        condition = "Mostly Clear",
        source = WeatherSource.NWS.id,
        precipProbability = precipProbability,
        cloudCover = cloudCover,
        fetchedAt = fetchedAt,
    )
}
