package com.weatherwidget.ui

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.NwsPointUnavailableException
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.data.remote.WeatherApiCredentialProvider
import com.weatherwidget.test.category.MediumDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(MediumDuration::class)
class SetupSourceSelectorTest {
    @Test
    fun `unsupported default sources add available WeatherAPI after survivors`() =
        runTest {
            val selector = selector(
                nws = SetupNwsCoverage.UNSUPPORTED,
                weatherApi = SetupWeatherApiAvailability.AVAILABLE,
                configured = true,
            )

            val result =
                selector.select(
                    current =
                        listOf(
                            WeatherSource.NWS,
                            WeatherSource.OPEN_METEO,
                            WeatherSource.SILURIAN,
                        ),
                    latitude = 51.5074,
                    longitude = -0.1278,
                )

            assertEquals(
                listOf(
                    WeatherSource.OPEN_METEO,
                    WeatherSource.SILURIAN,
                    WeatherSource.WEATHER_API,
                ),
                result.sources,
            )
            assertEquals(SetupWeatherApiAvailability.AVAILABLE, result.weatherApiAvailability)
        }

    @Test
    fun `unsupported NWS-only list falls back to Open-Meteo when WeatherAPI is unavailable`() =
        runTest {
            val selector = selector(
                nws = SetupNwsCoverage.UNSUPPORTED,
                weatherApi = SetupWeatherApiAvailability.UNAVAILABLE,
                configured = true,
            )

            val result =
                selector.select(
                    current = listOf(WeatherSource.NWS),
                    latitude = 51.5074,
                    longitude = -0.1278,
                )

            assertEquals(listOf(WeatherSource.OPEN_METEO), result.sources)
            assertEquals(SetupWeatherApiAvailability.UNAVAILABLE, result.weatherApiAvailability)
        }

    @Test
    fun `missing WeatherAPI key disables unsupported NWS without probing WeatherAPI`() =
        runTest {
            val checker = mockk<SetupSourceAvailabilityChecker>()
            coEvery { checker.checkNws(any(), any()) } returns
                (SetupNwsCoverage.UNSUPPORTED to "invalid_point")
            val credentialProvider = mockk<WeatherApiCredentialProvider>()
            every { credentialProvider.isConfigured() } returns false
            val selector = SetupSourceSelector(checker, credentialProvider)

            val result =
                selector.select(
                    current = listOf(WeatherSource.NWS, WeatherSource.SILURIAN),
                    latitude = 51.5074,
                    longitude = -0.1278,
                )

            assertEquals(listOf(WeatherSource.SILURIAN), result.sources)
            assertEquals(SetupWeatherApiAvailability.MISSING_KEY, result.weatherApiAvailability)
        }

    @Test
    fun `already enabled WeatherAPI is preserved without validation`() =
        runTest {
            val checker = mockk<SetupSourceAvailabilityChecker>()
            coEvery { checker.checkNws(any(), any()) } returns
                (SetupNwsCoverage.UNSUPPORTED to "invalid_point")
            val credentialProvider = mockk<WeatherApiCredentialProvider>(relaxed = true)
            val selector = SetupSourceSelector(checker, credentialProvider)

            val result =
                selector.select(
                    current =
                        listOf(
                            WeatherSource.NWS,
                            WeatherSource.WEATHER_API,
                            WeatherSource.OPEN_METEO,
                        ),
                    latitude = 51.5074,
                    longitude = -0.1278,
                )

            assertEquals(
                listOf(WeatherSource.WEATHER_API, WeatherSource.OPEN_METEO),
                result.sources,
            )
            assertEquals(
                SetupWeatherApiAvailability.ALREADY_ENABLED,
                result.weatherApiAvailability,
            )
        }

    @Test
    fun `supported and inconclusive NWS checks return exact source list instance`() =
        runTest {
            val current = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)

            val supported =
                selector(
                    nws = SetupNwsCoverage.SUPPORTED,
                    weatherApi = SetupWeatherApiAvailability.AVAILABLE,
                    configured = true,
                ).select(current, 37.42, -122.08)
            val inconclusive =
                selector(
                    nws = SetupNwsCoverage.INCONCLUSIVE,
                    weatherApi = SetupWeatherApiAvailability.AVAILABLE,
                    configured = true,
                ).select(current, 37.42, -122.08)

            assertSame(current, supported.sources)
            assertSame(current, inconclusive.sources)
        }

    @Test
    fun `unsupported result is exact no-op when NWS was already disabled`() =
        runTest {
            val current = listOf(WeatherSource.OPEN_METEO, WeatherSource.SILURIAN)

            val result =
                selector(
                    nws = SetupNwsCoverage.UNSUPPORTED,
                    weatherApi = SetupWeatherApiAvailability.AVAILABLE,
                    configured = true,
                ).select(current, 51.5074, -0.1278)

            assertSame(current, result.sources)
            assertEquals(SetupWeatherApiAvailability.NOT_CHECKED, result.weatherApiAvailability)
        }

    @Test
    fun `NWS checker distinguishes unsupported point from generic HTTP failure`() =
        runTest {
            val nwsApi = mockk<NwsApi>()
            val weatherApi = mockk<WeatherApi>(relaxed = true)
            val checker = SetupSourceAvailabilityChecker(nwsApi, weatherApi)
            coEvery { nwsApi.getGridPoint(any(), any()) } throws
                NwsPointUnavailableException("""{"type":"InvalidPoint"}""")

            val unsupported = checker.checkNws(51.5074, -0.1278)

            coEvery { nwsApi.getGridPoint(any(), any()) } throws
                ApiAccessException(
                    source = WeatherSource.NWS,
                    statusCode = 404,
                    detail = "not found",
                    message = "not found",
                )
            val unrelated404 = checker.checkNws(51.5074, -0.1278)

            assertEquals(SetupNwsCoverage.UNSUPPORTED, unsupported.first)
            assertEquals(SetupNwsCoverage.INCONCLUSIVE, unrelated404.first)
            assertEquals("http_404", unrelated404.second)
        }

    @Test
    fun `NWS checker classifies its bounded timeout as inconclusive`() =
        runTest {
            val nwsApi = mockk<NwsApi>()
            val weatherApi = mockk<WeatherApi>(relaxed = true)
            coEvery { nwsApi.getGridPoint(any(), any()) } coAnswers { awaitCancellation() }

            val result =
                SetupSourceAvailabilityChecker(nwsApi, weatherApi)
                    .checkNws(51.5074, -0.1278)

            assertEquals(SetupNwsCoverage.INCONCLUSIVE, result.first)
            assertEquals("timeout", result.second)
        }

    @Test
    fun `WeatherAPI checker requires a nonempty forecast`() =
        runTest {
            val nwsApi = mockk<NwsApi>(relaxed = true)
            val weatherApi = mockk<WeatherApi>()
            val checker = SetupSourceAvailabilityChecker(nwsApi, weatherApi)
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
            val nwsApi = mockk<NwsApi>(relaxed = true)
            val weatherApi = mockk<WeatherApi>()
            val checker = SetupSourceAvailabilityChecker(nwsApi, weatherApi)
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
    fun `NWS checker propagates cancellation`() =
        runTest {
            val nwsApi = mockk<NwsApi>()
            val weatherApi = mockk<WeatherApi>(relaxed = true)
            coEvery { nwsApi.getGridPoint(any(), any()) } throws CancellationException("cancel")

            SetupSourceAvailabilityChecker(nwsApi, weatherApi).checkNws(51.5074, -0.1278)
        }

    @Test(expected = CancellationException::class)
    fun `WeatherAPI checker propagates cancellation`() =
        runTest {
            val nwsApi = mockk<NwsApi>(relaxed = true)
            val weatherApi = mockk<WeatherApi>()
            coEvery { weatherApi.getForecast(any(), any(), any()) } throws CancellationException("cancel")

            SetupSourceAvailabilityChecker(nwsApi, weatherApi)
                .checkWeatherApi(51.5074, -0.1278)
        }

    private fun selector(
        nws: SetupNwsCoverage,
        weatherApi: SetupWeatherApiAvailability,
        configured: Boolean,
    ): SetupSourceSelector {
        val checker = mockk<SetupSourceAvailabilityChecker>()
        coEvery { checker.checkNws(any(), any()) } returns (nws to null)
        coEvery { checker.checkWeatherApi(any(), any()) } returns (weatherApi to null)
        val credentialProvider = mockk<WeatherApiCredentialProvider>()
        every { credentialProvider.isConfigured() } returns configured
        return SetupSourceSelector(checker, credentialProvider)
    }
}
