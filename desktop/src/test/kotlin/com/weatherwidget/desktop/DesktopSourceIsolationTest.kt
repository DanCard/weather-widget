package com.weatherwidget.desktop

import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopSourceIsolationTest {

    @Test
    fun `NWS observation failure is exposed instead of falling back to Open-Meteo`() = runTest {
        val nws = mockk<NwsApi>()
        coEvery { nws.getGridPoint(any(), any()) } throws IllegalStateException("nws unavailable")
        val service = DesktopWeatherService(
            latitude = 37.422,
            longitude = -122.084,
            weatherSource = "NWS",
            injectedNwsApi = nws,
        )

        try {
            service.fetchObservationsOnly()
            fail("Expected the selected NWS provider failure")
        } catch (error: IllegalStateException) {
            assertEquals("nws unavailable", error.message)
        } finally {
            service.close()
        }
    }
}
