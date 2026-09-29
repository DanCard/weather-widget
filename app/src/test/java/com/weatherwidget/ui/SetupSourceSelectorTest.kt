package com.weatherwidget.ui

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.data.remote.WeatherApiCredentialProvider
import com.weatherwidget.test.category.MediumDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The setup screen no longer adds or removes NWS: whether NWS can serve a site is derived per
 * location (`SourceCoverage`) and never written to the enabled list (2026-09-29). Its one remaining
 * edit is offering WeatherAPI as NWS's stand-in when a save leaves coverage.
 */
@Category(MediumDuration::class)
class SetupSourceSelectorTest {
    private val london = 51.5074 to -0.1278
    private val warsaw = 52.2334 to 21.0711
    private val mountainView = 37.4168 to -122.0890

    @Test
    fun `leaving coverage keeps NWS enabled and adds available WeatherAPI`() =
        runTest {
            val result = selector(weatherApi = SetupWeatherApiAvailability.AVAILABLE, configured = true)
                .select(
                    current = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN),
                    latitude = london.first,
                    longitude = london.second,
                    previous = mountainView,
                )

            assertEquals(
                listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN, WeatherSource.WEATHER_API),
                result.sources,
            )
            assertEquals(SetupNwsCoverage.UNSUPPORTED, result.nwsCoverage)
            assertEquals(SetupWeatherApiAvailability.AVAILABLE, result.weatherApiAvailability)
        }

    @Test
    fun `leaving coverage without a WeatherAPI key changes nothing and does not probe`() =
        runTest {
            val checker = mockk<SetupSourceAvailabilityChecker>()
            val credentialProvider = mockk<WeatherApiCredentialProvider>()
            every { credentialProvider.isConfigured() } returns false
            val current = listOf(WeatherSource.NWS, WeatherSource.SILURIAN)

            val result = SetupSourceSelector(checker, credentialProvider)
                .select(current, london.first, london.second, previous = mountainView)

            assertSame(current, result.sources)
            assertEquals(SetupWeatherApiAvailability.MISSING_KEY, result.weatherApiAvailability)
            coVerify(exactly = 0) { checker.checkWeatherApi(any(), any()) }
        }

    @Test
    fun `already enabled WeatherAPI is preserved without validation`() =
        runTest {
            val current = listOf(WeatherSource.NWS, WeatherSource.WEATHER_API, WeatherSource.OPEN_METEO)

            val result = selector(weatherApi = SetupWeatherApiAvailability.UNAVAILABLE, configured = true)
                .select(current, london.first, london.second, previous = mountainView)

            assertSame(current, result.sources)
            assertEquals(SetupWeatherApiAvailability.ALREADY_ENABLED, result.weatherApiAvailability)
        }

    @Test
    fun `inside coverage the enabled list is returned as the same instance`() =
        runTest {
            val current = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)
            val result = selector(weatherApi = SetupWeatherApiAvailability.AVAILABLE, configured = true)
                .select(current, mountainView.first, mountainView.second, previous = warsaw)

            assertSame(current, result.sources)
            assertEquals(SetupNwsCoverage.SUPPORTED, result.nwsCoverage)
        }

    @Test
    fun `returning to coverage does not need to restore anything`() =
        runTest {
            // The 2026-09-29 report: Warsaw -> Mountain View. NWS was never removed, so the list
            // that comes back is the one that went out.
            val selector = selector(weatherApi = SetupWeatherApiAvailability.NOT_CHECKED, configured = false)
            val enabled = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN)

            val inWarsaw = selector.select(enabled, warsaw.first, warsaw.second, previous = mountainView)
            val backHome = selector.select(inWarsaw.sources, mountainView.first, mountainView.second, previous = warsaw)

            assertEquals(enabled, backHome.sources)
        }

    @Test
    fun `a second save outside coverage does not re-add an unticked WeatherAPI`() =
        runTest {
            val checker = mockk<SetupSourceAvailabilityChecker>()
            val credentialProvider = mockk<WeatherApiCredentialProvider>()
            every { credentialProvider.isConfigured() } returns true
            val current = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)

            val result = SetupSourceSelector(checker, credentialProvider)
                .select(current, london.first, london.second, previous = warsaw)

            assertSame(current, result.sources)
            assertEquals("already_outside_coverage", result.reason)
            coVerify(exactly = 0) { checker.checkWeatherApi(any(), any()) }
        }

    @Test
    fun `without NWS enabled there is nothing to stand in for`() =
        runTest {
            val current = listOf(WeatherSource.OPEN_METEO, WeatherSource.SILURIAN)
            val result = selector(weatherApi = SetupWeatherApiAvailability.AVAILABLE, configured = true)
                .select(current, london.first, london.second, previous = mountainView)

            assertSame(current, result.sources)
            assertEquals(SetupWeatherApiAvailability.NOT_CHECKED, result.weatherApiAvailability)
        }

    @Test
    fun `WeatherAPI checker requires a nonempty forecast`() =
        runTest {
            val weatherApi = mockk<WeatherApi>()
            val checker = SetupSourceAvailabilityChecker(weatherApi)
            coEvery { weatherApi.getForecast(any(), any(), any()) } returns RawFetch()

            val empty = checker.checkWeatherApi(51.5074, -0.1278)

            coEvery { weatherApi.getForecast(any(), any(), any()) } returns
                RawFetch(
                    daily =
                        listOf(
                            DailyForecast(
                                date = "2026-07-28",
                                highTemp = 70f,
                                lowTemp = 55f,
                                condition = "Cloudy",
                            ),
                        ),
                )
            val available = checker.checkWeatherApi(51.5074, -0.1278)

            assertEquals(SetupWeatherApiAvailability.UNAVAILABLE, empty.first)
            assertEquals("empty_forecast", empty.second)
            assertEquals(SetupWeatherApiAvailability.AVAILABLE, available.first)
            coVerify(atLeast = 1) {
                weatherApi.getForecast(51.5074, -0.1278, days = 1)
            }
        }

    @Test
    fun `WeatherAPI checker reports access and quota failures without enabling`() =
        runTest {
            val weatherApi = mockk<WeatherApi>()
            val checker = SetupSourceAvailabilityChecker(weatherApi)
            coEvery { weatherApi.getForecast(any(), any(), any()) } throws
                ApiAccessException(
                    source = WeatherSource.WEATHER_API,
                    statusCode = 401,
                    detail = "invalid key",
                    message = "invalid key",
                )

            val unauthorized = checker.checkWeatherApi(51.5074, -0.1278)

            coEvery { weatherApi.getForecast(any(), any(), any()) } throws
                ApiAccessException(
                    source = WeatherSource.WEATHER_API,
                    statusCode = 429,
                    detail = "quota",
                    message = "quota",
                )
            val quota = checker.checkWeatherApi(51.5074, -0.1278)

            assertEquals(SetupWeatherApiAvailability.UNAVAILABLE, unauthorized.first)
            assertEquals("http_401", unauthorized.second)
            assertEquals(SetupWeatherApiAvailability.UNAVAILABLE, quota.first)
            assertEquals("http_429", quota.second)
        }

    @Test(expected = CancellationException::class)
    fun `WeatherAPI checker propagates cancellation`() =
        runTest {
            val weatherApi = mockk<WeatherApi>()
            coEvery { weatherApi.getForecast(any(), any(), any()) } throws CancellationException("cancel")

            SetupSourceAvailabilityChecker(weatherApi)
                .checkWeatherApi(51.5074, -0.1278)
        }

    private fun selector(
        weatherApi: SetupWeatherApiAvailability,
        configured: Boolean,
    ): SetupSourceSelector {
        val checker = mockk<SetupSourceAvailabilityChecker>()
        coEvery { checker.checkWeatherApi(any(), any()) } returns (weatherApi to null)
        val credentialProvider = mockk<WeatherApiCredentialProvider>()
        every { credentialProvider.isConfigured() } returns configured
        return SetupSourceSelector(checker, credentialProvider)
    }
}
