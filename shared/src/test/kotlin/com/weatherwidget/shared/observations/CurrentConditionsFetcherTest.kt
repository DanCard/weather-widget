package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * One contract for the model feeds' current conditions, run once for Android and desktop
 * (plans/261013-shared-current-conditions-fetch.md). Before this, Android sampled five points under
 * "Meteo: Current" names while desktop sampled one under "Open-Meteo Current", and desktop had no
 * WeatherAPI current path at all.
 */
@Category(ShortDuration::class)
class CurrentConditionsFetcherTest {
    private val lat = 37.4169
    private val lon = -122.0889
    private val now = 1_791_900_000_000L
    private val requested = mutableListOf<Pair<Double, Double>>()

    private fun fetcher(calls: CurrentConditionsFetcher.Calls) = CurrentConditionsFetcher(calls) { now }

    @Test
    fun `Open-Meteo - one centre request, MAIN row with provider cloud bands`() = runBlocking {
        val result = fetcher(
            CurrentConditionsFetcher.Calls(
                openMeteoCurrent = { la, lo ->
                    requested += la to lo
                    OpenMeteoApi.CurrentReading(71.8f, 1, observedAt = now - 300_000, cloudCover = 20, cloudCoverLow = 10)
                },
                openMeteoCondition = { if (it == 1) "Mainly Clear" else "?" },
            ),
        ).fetch(WeatherSource.OPEN_METEO, lat, lon)!!

        assertEquals("centre point only: virtual stations (user, 2026-10-13)", listOf(lat to lon), requested)
        val row = result.readings.single()
        assertEquals("OPEN_METEO_MAIN", row.stationId)
        assertEquals("Meteo: Current", row.stationName)
        assertEquals(now - 300_000, row.timestamp)
        assertEquals(71.8f, row.temperature)
        assertEquals("Mainly Clear", row.condition)
        assertEquals(0f, row.distanceKm)
        assertEquals(StationType.OFFICIAL, row.stationType)
        assertEquals(CloudVerticalKind.PROVIDER_BANDS, row.cloudVerticalKind)
        assertEquals(10, row.cloudCoverLow)
        assertEquals(CurrentConditionsFetcher.Current(WeatherSource.OPEN_METEO, 71.8f, "Mainly Clear", now - 300_000), result.current)
    }

    @Test
    fun `WeatherAPI and OWM - current from the forecast response, stamped now when the provider gives no time`() = runBlocking {
        val forecast: suspend (Double, Double) -> RawFetch = { la, lo ->
            requested += la to lo
            RawFetch(providerCurrentTemp = 70.5f, providerCurrentCondition = "Sunny")
        }
        val calls = CurrentConditionsFetcher.Calls(weatherApiForecast = forecast, openWeatherMapForecast = forecast)

        val wapi = fetcher(calls).fetch(WeatherSource.WEATHER_API, lat, lon)!!.readings.single()
        val owm = fetcher(calls).fetch(WeatherSource.OPEN_WEATHER_MAP, lat, lon)!!.readings.single()

        assertEquals(listOf(lat to lon, lat to lon), requested)
        assertEquals("WEATHER_API_MAIN" to "WAPI: Current", wapi.stationId to wapi.stationName)
        assertEquals("OPEN_WEATHER_MAP_MAIN" to "OWM: Current", owm.stationId to owm.stationName)
        assertEquals(now, owm.timestamp)
        assertEquals(StationType.OFFICIAL, owm.stationType)
        assertEquals(WeatherSource.OPEN_WEATHER_MAP.id, owm.api)
    }

    @Test
    fun `a response without a current value stores nothing`() = runBlocking {
        val result = fetcher(CurrentConditionsFetcher.Calls(openWeatherMapForecast = { _, _ -> RawFetch() }))
            .fetch(WeatherSource.OPEN_WEATHER_MAP, lat, lon)!!
        assertTrue(result.readings.isEmpty())
        assertNull(result.current)
    }

    @Test
    fun `Tomorrow-io - every five-minute row, the newest as current`() = runBlocking {
        var hoursAsked = -1
        val rows = listOf(now - 600_000 to 70f, now - 300_000 to 71f)
        val result = fetcher(
            CurrentConditionsFetcher.Calls(
                tomorrowIoFiveMinuteHistory = { _, _, hours ->
                    hoursAsked = hours
                    RawFetch(subHourly = rows.map { (t, temp) -> HourlyForecast(t, temp, "Clear") })
                },
            ),
        ).fetch(WeatherSource.TOMORROW_IO, lat, lon)!!

        assertEquals(com.weatherwidget.data.remote.TomorrowIoApi.INCREMENTAL_ACTUALS_LOOKBACK_HOURS, hoursAsked)
        assertEquals(2, result.readings.size)
        assertEquals(71f, result.current!!.temperature)
        assertEquals(now - 300_000, result.current!!.observedAt)
    }

    @Test
    fun `a refused request propagates, so the platform records the failure`() = runBlocking {
        val refused = ApiAccessException(WeatherSource.OPEN_WEATHER_MAP, 429, "quota", "OWM 429")
        try {
            fetcher(CurrentConditionsFetcher.Calls(openWeatherMapForecast = { _, _ -> throw refused }))
                .fetch(WeatherSource.OPEN_WEATHER_MAP, lat, lon)
            fail("expected the ApiAccessException")
        } catch (e: ApiAccessException) {
            assertEquals(429, e.statusCode)
        }
    }

    @Test
    fun `station networks, forecast-only sources and unconfigured clients are not served`() = runBlocking {
        val none = fetcher(CurrentConditionsFetcher.Calls())
        for (feed in WeatherSource.entries) {
            assertNull("$feed with no clients", none.fetch(feed, lat, lon))
        }
        assertEquals(
            setOf(WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API, WeatherSource.OPEN_WEATHER_MAP, WeatherSource.TOMORROW_IO),
            CurrentConditionsFetcher.FEEDS,
        )
    }
}

@Category(ShortDuration::class)
class CurrentConditionsCallSitesTest {
    @Test
    fun `a direct call outside the allowance is an offender, comments are not`() {
        val offenders = CurrentConditionsCallSites.offenders(
            mapOf(
                "Screen.kt" to "val r = openMeteo.getCurrent(lat, lon)\n// tomorrowIo.getFiveMinuteHistory(lat, lon, 2)",
                "DesktopWeatherService.kt" to "tomorrowIo.getFiveMinuteHistory(a, b, 24)\ntomorrowIo.getFiveMinuteHistory(a, b, 2)",
                "ForecastFetchCoordinator.kt" to "api.getFiveMinuteHistory(lat, lon, 24)",
            ),
        )
        assertEquals(
            listOf("Screen.kt: 1 direct call(s), allowed 0", "DesktopWeatherService.kt: 2 direct call(s), allowed 1"),
            offenders,
        )
    }
}
