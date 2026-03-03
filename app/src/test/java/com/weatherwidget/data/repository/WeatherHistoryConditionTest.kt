package com.weatherwidget.data.repository

import android.content.Context
import com.weatherwidget.data.remote.NwsApi
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class WeatherHistoryConditionTest {
    private lateinit var context: Context
    private lateinit var nwsApi: NwsApi
    private lateinit var forecastRepo: ForecastRepository
    private lateinit var repository: WeatherRepository

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        nwsApi = mockk()
        forecastRepo = ForecastRepository(context, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), nwsApi, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        repository =
            WeatherRepository(
                context,
                forecastRepo,
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
            )
    }

    @Test
    fun `fetchDayObservations calculates Sunny for clear observations`() =
        runTest {
            val date = LocalDate.now()
            val stationsUrl = "https://api.weather.gov/stations"
            coEvery { nwsApi.getObservationStations(any()) } returns listOf(NwsApi.StationInfo("KSFO", "San Francisco", 0.0, 0.0, NwsApi.StationType.OFFICIAL))
            coEvery { nwsApi.getObservations(any(), any(), any()) } returns
                listOf(
                    NwsApi.Observation("2026-02-04T18:00:00Z", 20.0f, "Clear"),
                    NwsApi.Observation("2026-02-04T19:00:00Z", 21.0f, "Sunny"),
                )
            val result = forecastRepo.fetchDayObservations(stationsUrl, date)
            assertEquals("Sunny", result?.condition)
        }

    @Test
    fun `fetchDayObservations calculates Mostly Sunny for 25 percent cloud coverage`() =
        runTest {
            val date = LocalDate.now()
            coEvery { nwsApi.getObservationStations(any()) } returns listOf(NwsApi.StationInfo("KSFO", "San Francisco", 0.0, 0.0, NwsApi.StationType.OFFICIAL))
            coEvery { nwsApi.getObservations(any(), any(), any()) } returns
                listOf(
                    NwsApi.Observation("2026-02-04T18:00:00Z", 20.0f, "Clear"),
                    NwsApi.Observation("2026-02-04T19:00:00Z", 21.0f, "Mostly Clear"),
                    NwsApi.Observation("2026-02-04T20:00:00Z", 22.0f, "Mostly Sunny"),
                    NwsApi.Observation("2026-02-04T21:00:00Z", 23.0f, "Partly Cloudy"),
                )
            val result = forecastRepo.fetchDayObservations("url", date)
            assertEquals("Mostly Sunny (25%)", result?.condition)
        }
}
