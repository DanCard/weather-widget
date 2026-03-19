package com.weatherwidget.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.data.local.*
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WeatherRepositoryPoiTest {
    private lateinit var context: Context
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var forecastDao: ForecastDao
    private lateinit var appLogDao: AppLogDao
    private lateinit var observationDao: ObservationDao
    private lateinit var repository: WeatherRepository

    private val testLat = 37.422
    private val testLon = -122.084

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        sharedPrefs = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs

        forecastDao = mockk(relaxed = true)
        appLogDao = mockk(relaxed = true)
        observationDao = mockk(relaxed = true)

        val forecastRepo = ForecastRepository(context, forecastDao, mockk(relaxed = true), appLogDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), observationDao, mockk(relaxed = true), mockk(relaxed = true))
        val currentRepo = CurrentTempRepository(context, mockk(relaxed = true), observationDao, mockk(relaxed = true), appLogDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), TemperatureInterpolator(), mockk(relaxed = true), mockk(relaxed = true))

        repository = WeatherRepository(context, forecastRepo, currentRepo, forecastDao, appLogDao, mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun `recordHistoricalPoi stores and rotates unique locations`() {
        val slot = slot<String>()
        var storedString = ""
        every { sharedPrefs.edit().putString("historical_pois", capture(slot)).apply() } answers {
            storedString = slot.captured
            mockk(relaxed = true)
        }
        every { sharedPrefs.getString("historical_pois", "") } answers { storedString }

        repository.recordHistoricalPoi(testLat, testLon, "Mountain View")
        assertEquals("Mountain View|37.422|-122.084", storedString)
    }

    @Test
    fun `getHistoricalPois applies user aliases correctly`() {
        every { sharedPrefs.getString("historical_pois", "") } returns "Mountain View|37.422|-122.084"
        val result = repository.getHistoricalPois()
        assertEquals(1, result.size)
        assertEquals("Mountain View", result[0].third)
        assertEquals(37.422, result[0].first, 0.001)
    }
}
