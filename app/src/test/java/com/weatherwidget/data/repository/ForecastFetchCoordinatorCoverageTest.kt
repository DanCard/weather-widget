package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * The network choke point never sends NWS a point outside its coverage (a guaranteed 404
 * InvalidPoint), whatever set of sources a caller hands it.
 *
 * Since 2026-09-29 NWS stays in the user's enabled list abroad and is filtered per location
 * (`SourceCoverage`). `WidgetStateManager.getVisibleSourcesOrder()` filters by the *stored* active
 * location; this guards the fetch against any caller that reads the raw list, or fetches for
 * coordinates other than the stored ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class ForecastFetchCoordinatorCoverageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nwsMapper: NwsForecastMapper = mockk()
    private val nwsActuals: NwsApiDailyActualsFetcher = mockk(relaxed = true)

    private fun coordinator(): ForecastFetchCoordinator {
        coEvery { nwsMapper.fetchFromNws(any(), any()) } returns NwsForecastMapper.NwsFetchResult(emptyList(), emptyList())
        return ForecastFetchCoordinator(
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
            weatherApiHistoryBackfiller = mockk(relaxed = true),
            nwsApiDailyActualsFetcher = nwsActuals,
        )
    }

    @Test
    fun `NWS is never fetched for a point outside its coverage`() = runBlocking {
        coordinator().fetchFromAllApis(52.2334, 21.0711, setOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))

        coVerify(exactly = 0) { nwsMapper.fetchFromNws(any(), any()) }
        coVerify(exactly = 0) { nwsActuals.fillMissingIfNeeded(any(), any()) }
    }

    @Test
    fun `NWS is fetched inside its coverage`() = runBlocking {
        // The presence case: without it the absence above would pass against a coordinator
        // that never fetches NWS at all.
        coordinator().fetchFromAllApis(37.4166, -122.0889, setOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))

        coVerify(exactly = 1) { nwsMapper.fetchFromNws(37.4166, -122.0889) }
        coVerify(exactly = 1) { nwsActuals.fillMissingIfNeeded(37.4166, -122.0889) }
    }
}
