package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.MediumDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What rides a stored fetch is `:shared` `SourceFetchTable.followUps`, as on desktop
 * (`plans/261025-shared-source-fetch-table.md`, F4/F5): WeatherAPI history and NWS station actuals only
 * on a daily fetch of their own source; NWS station actuals even when the NWS forecast fetch failed; none on
 * an on-demand single-source fetch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class ForecastFetchCoordinatorFollowUpsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nwsMapper: NwsForecastMapper = mockk()
    private val nwsActuals: NwsApiDailyActualsFetcher = mockk(relaxed = true)
    private val weatherApiHistory: WeatherApiHistoryBackfiller = mockk(relaxed = true)
    private val lat = 37.4166
    private val lon = -122.0889

    private fun coordinator() = ForecastFetchCoordinator(
        context = context,
        appLogDao = mockk(relaxed = true),
        openMeteoApi = mockk(relaxed = true),
        weatherApi = mockk(relaxed = true),
        silurianApi = mockk(relaxed = true),
        widgetStateManager = mockk(relaxed = true),
        tomorrowIoApi = null,
        openWeatherMapApi = null,
        nwsForecastMapper = nwsMapper,
        snapshotStore = mockk(relaxed = true),
        hourlyStore = mockk(relaxed = true),
        weatherApiHistoryBackfiller = weatherApiHistory,
        nwsApiDailyActualsFetcher = nwsActuals,
    )

    @Test
    fun `daily fetches run their own source's follow-up`() = runBlocking {
        coEvery { nwsMapper.fetchFromNws(any(), any(), any()) } returns RawFetch()

        coordinator().fetchFromAllApis(lat, lon, mapOf(WeatherSource.NWS to ForecastParts.BOTH, WeatherSource.WEATHER_API to ForecastParts.BOTH))

        coVerify(exactly = 1) { nwsActuals.fillMissingIfNeeded(lat, lon) }
        coVerify(exactly = 1) { weatherApiHistory.backfillIfNeeded(lat, lon) }
    }

    @Test
    fun `hourly-only fetches run neither history follow-up`() = runBlocking {
        coEvery { nwsMapper.fetchFromNws(any(), any(), any()) } returns RawFetch()

        coordinator().fetchFromAllApis(lat, lon, mapOf(WeatherSource.NWS to ForecastParts.HOURLY, WeatherSource.WEATHER_API to ForecastParts.HOURLY))

        coVerify(exactly = 0) { nwsActuals.fillMissingIfNeeded(any(), any()) }
        coVerify(exactly = 0) { weatherApiHistory.backfillIfNeeded(any(), any()) }
    }

    @Test
    fun `a failed NWS forecast fetch still runs station actuals`() = runBlocking {
        // The station endpoints are not the gridpoint ones: a forecast outage keeps the actuals (user, 2026-10-25).
        coEvery { nwsMapper.fetchFromNws(any(), any(), any()) } throws java.io.IOException("gridpoints 500")

        coordinator().fetchFromAllApis(lat, lon, mapOf(WeatherSource.NWS to ForecastParts.BOTH))

        coVerify(exactly = 1) { nwsActuals.fillMissingIfNeeded(lat, lon) }
    }

    @Test
    fun `only another source fetched runs no NWS station actuals`() = runBlocking {
        coordinator().fetchFromAllApis(lat, lon, mapOf(WeatherSource.OPEN_METEO to ForecastParts.BOTH))

        coVerify(exactly = 0) { nwsActuals.fillMissingIfNeeded(any(), any()) }
        coVerify(exactly = 0) { weatherApiHistory.backfillIfNeeded(any(), any()) }
    }

    @Test
    fun `an on-demand single-source fetch runs no follow-up`() = runBlocking {
        // As desktop's on-demand fetch (extendHourlyFor); Android ran WeatherAPI history here.
        coordinator().fetchSingleSource(
            lat,
            lon,
            WeatherSource.WEATHER_API,
            com.weatherwidget.widget.ForecastFetchContext(isCharging = false, isScreenInteractive = true, batteryLevel = 80, activeSourceIds = setOf(WeatherSource.WEATHER_API.id)),
        )

        coVerify(exactly = 0) { weatherApiHistory.backfillIfNeeded(any(), any()) }
    }
}
