package com.weatherwidget.data.repository

import com.weatherwidget.data.remote.NwsApi
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            NwsApi.Observation("2026-02-04T18:00:00Z", 20.0, "Clear"),
            NwsApi.Observation("2026-02-04T19:00:00Z", 21.0, "Sunny")
        )

        val result = repository.fetchDayObservations(stationsUrl, date)
        
        assertEquals("Sunny", result?.fourth)
    }

    @Test
    fun `fetchDayObservations calculates Mostly Sunny for 25 percent cloud coverage`() = runTest {
        val date = LocalDate.now()
        
        coEvery { nwsApi.getObservationStations(any()) } returns listOf("KSFO")
        coEvery { nwsApi.getObservations(any(), any(), any()) } returns listOf(
            NwsApi.Observation("2026-02-04T18:00:00Z", 20.0, "Clear"),
            NwsApi.Observation("2026-02-04T19:00:00Z", 21.0, "Mostly Clear"),
            NwsApi.Observation("2026-02-04T20:00:00Z", 22.0, "Mostly Sunny"),
            NwsApi.Observation("2026-02-04T21:00:00Z", 23.0, "Partly Cloudy")
        )
        // (0 + 25 + 25 + 50) / 4 = 100 / 4 = 25%

        val result = repository.fetchDayObservations("url", date)
        
        assertEquals("Mostly Sunny (25%)", result?.fourth)
    }

    @Test
    fun `fetchDayObservations calculates Mostly Cloudy for 75 percent cloud coverage`() = runTest {
        val date = LocalDate.now()
        
        coEvery { nwsApi.getObservationStations(any()) } returns listOf("KSFO")
        coEvery { nwsApi.getObservations(any(), any(), any()) } returns listOf(
            NwsApi.Observation("2026-02-04T18:00:00Z", 20.0, "Cloudy"),
            NwsApi.Observation("2026-02-04T19:00:00Z", 21.0, "Mostly Cloudy"),
            NwsApi.Observation("2026-02-04T20:00:00Z", 22.0, "Overcast"),
            NwsApi.Observation("2026-02-04T21:00:00Z", 23.0, "Partly Cloudy")
        )
        // (100 + 75 + 100 + 50) / 4 = 325 / 4 = 81.25% (Mostly Cloudy bucket)

        val result = repository.fetchDayObservations("url", date)
        
        assertEquals("Mostly Cloudy (75%)", result?.fourth)
    }
}