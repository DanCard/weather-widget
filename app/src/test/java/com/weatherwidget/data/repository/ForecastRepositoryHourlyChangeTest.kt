package com.weatherwidget.data.repository

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
class ForecastRepositoryHourlyChangeTest {

    @Test
    fun `siteExactExistingByDateTime ignores a fresher row from a different coordinate fragment`() {
        // 2026-07-10 Samsung bug: a GPS-jitter fragment absorbed the newest fetch (96% cloud),
        // then the change gate compared the next fetch at the display site against THAT row,
        // saw "no change", and never wrote — pinning the display site at the stale 67%. Every hour is
        // written now (HourlyForecastWrite), but a fragment must still never be the row merged from.
        val displaySiteStale = hourly(cloudCover = 67, fetchedAt = 1_000L, lat = 37.417, lon = -122.089)
        val jitterFragmentFresh = hourly(cloudCover = 96, fetchedAt = 9_000L, lat = 37.422, lon = -122.087)

        val existing = ForecastRepository.siteExactExistingByDateTime(
            listOf(displaySiteStale, jitterFragmentFresh),
            lat = 37.417,
            lon = -122.089,
        )

        assertEquals(displaySiteStale, existing[displaySiteStale.dateTime])
    }

    @Test
    fun `siteExactExistingByDateTime is empty when only other fragments have rows`() {
        // Brand-new site: nothing to merge from, rather than a neighbour's fields leaking in.
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
