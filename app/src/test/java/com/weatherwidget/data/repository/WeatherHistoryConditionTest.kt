package com.weatherwidget.data.repository

import com.weatherwidget.data.remote.NwsApi
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class WeatherHistoryConditionTest {

    private lateinit var nwsApi: NwsApi
    private lateinit var repository: WeatherRepository

    @Before
    fun setup() {
        nwsApi = mockk()
        repository = WeatherRepository(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), nwsApi,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true)
        )
    }

    @Test
    fun `fetchDayObservations calculates Sunny for clear observations`() = runTest {
        val date = LocalDate.now()
        val stationsUrl = "https://api.weather.gov/stations"
        
        coEvery { nwsApi.getObservationStations(any()) } returns listOf("KSFO")
        coEvery { nwsApi.getObservations(any(), any(), any()) } returns listOf(
            NwsApi.Observation("2026-02-04T18:00:00Z", 20.0f, "Clear"),
            NwsApi.Observation("2026-02-04T19:00:00Z", 21.0f, "Sunny")
        )

        val result = repository.fetchDayObservations(stationsUrl, date)
        
        assertEquals("Sunny", result?.fourth)
    }

    @Test
    fun `fetchDayObservations calculates Mostly Sunny for 25 percent cloud coverage`() = runTest {
        val date = LocalDate.now()
        
        coEvery { nwsApi.getObservationStations(any()) } returns listOf("KSFO")
        coEvery { nwsApi.getObservations(any(), any(), any()) } returns listOf(
            NwsApi.Observation("2026-02-04T18:00:00Z", 20.0f, "Clear"),
            NwsApi.Observation("2026-02-04T19:00:00Z", 21.0f, "Mostly Clear"),
            NwsApi.Observation("2026-02-04T20:00:00Z", 22.0f, "Mostly Sunny"),
            NwsApi.Observation("2026-02-04T21:00:00Z", 23.0f, "Partly Cloudy")
        )
        // (0 + 25 + 25 + 50) / 4 = 100 / 4 = 25%

        val result = repository.fetchDayObservations("url", date)
        
        assertEquals("Mostly Sunny (25%)", result?.fourth)
    }

    @Test
    fun `fetchDayObservations calculates Sunny for Fair observations`() = runTest {
        val date = LocalDate.now()
        
        coEvery { nwsApi.getObservationStations(any()) } returns listOf("KSFO")
        coEvery { nwsApi.getObservations(any(), any(), any()) } returns listOf(
            NwsApi.Observation("2026-02-04T18:00:00Z", 20.0f, "Fair")
        )

        val result = repository.fetchDayObservations("url", date)
        
        assertEquals("Sunny", result?.fourth)
    }

    @Test
    fun `fetchFromNws uses forecast summary for todays condition`() = runTest {
        val lat = 37.422
        val lon = -122.0841
        val today = LocalDate.now()
        val todayStr = today.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        
        val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "forecast", "stations")
        coEvery { nwsApi.getGridPoint(lat, lon) } returns gridPoint
        coEvery { nwsApi.getObservationStations(any()) } returns listOf("KSFO")
        coEvery { nwsApi.getObservations(any(), any(), any()) } returns listOf(
            NwsApi.Observation("${todayStr}T12:00:00Z", 20.0f, "Clear")
        )
        coEvery { nwsApi.getForecast(any()) } returns listOf(
            NwsApi.ForecastPeriod("Today", "${todayStr}T06:00:00-08:00", 72, "F", "Cloudy", true)
        )
        coEvery { nwsApi.getHourlyForecast(any()) } returns emptyList()

        // Call fetchFromNws directly
        val result = repository.fetchFromNws(lat, lon, "Location")
        val data = result.find { it.date == todayStr }
        
        // For today, repository prefers daily forecast shortForecast as whole-day summary.
        assertEquals("Cloudy", data?.condition)
    }

    @Test
    fun `fetchFromNws uses tonight forecast condition when todays first period is nighttime`() = runTest {
        val lat = 37.422
        val lon = -122.0841
        val today = LocalDate.now()
        val todayStr = today.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val tomorrowStr = today.plusDays(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)

        val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "forecast", "stations")
        coEvery { nwsApi.getGridPoint(lat, lon) } returns gridPoint
        coEvery { nwsApi.getObservationStations(any()) } returns listOf("KSFO")
        coEvery { nwsApi.getObservations(any(), any(), any()) } returns listOf(
            NwsApi.Observation("${todayStr}T12:00:00Z", 20.0f, "Clear")
        )
        coEvery { nwsApi.getForecast(any()) } returns listOf(
            NwsApi.ForecastPeriod("Tonight", "${todayStr}T18:00:00-08:00", 47, "F", "Areas Of Fog", false),
            NwsApi.ForecastPeriod("Saturday", "${tomorrowStr}T06:00:00-08:00", 64, "F", "Mostly Sunny", true)
        )
        coEvery { nwsApi.getHourlyForecast(any()) } returns emptyList()

        val result = repository.fetchFromNws(lat, lon, "Location")
        val data = result.find { it.date == todayStr }

        assertEquals("Areas Of Fog", data?.condition)
    }

    @Test
    fun `fetchFromNws assigns malformed startTime periods to parsed ISO date prefix`() = runTest {
        val lat = 37.422
        val lon = -122.0841
        val todayStr = LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val tomorrowStr = LocalDate.now().plusDays(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)

        val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "forecast", null)
        coEvery { nwsApi.getGridPoint(lat, lon) } returns gridPoint
        coEvery { nwsApi.getForecast(any()) } returns listOf(
            NwsApi.ForecastPeriod("Tomorrow", "$tomorrowStr invalid-time", 68, "F", "Partly Cloudy", true),
            NwsApi.ForecastPeriod("Tomorrow Night", "$tomorrowStr still-invalid", 50, "F", "Clear", false)
        )
        coEvery { nwsApi.getHourlyForecast(any()) } returns emptyList()

        val result = repository.fetchFromNws(lat, lon, "Location")
        val todayData = result.find { it.date == todayStr }
        val tomorrowData = result.find { it.date == tomorrowStr }

        assertNull(todayData)
        assertNotNull(tomorrowData)
        assertEquals(68, tomorrowData?.highTemp)
        assertEquals(50, tomorrowData?.lowTemp)
    }
}
