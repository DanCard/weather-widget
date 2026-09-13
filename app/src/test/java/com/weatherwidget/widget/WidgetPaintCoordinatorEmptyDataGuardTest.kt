package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * `updateAllWidgets` with nothing to draw must push nothing.
 *
 * Seen 2026-09-13 while verifying the setup-location interstitial offline: the force-refresh
 * "succeeded" with 0 rows, the pending-fetch fallback painted "Tap to refresh", and then a
 * follow-up `missing_actuals` sync — also 0 rows — pushed an empty grey graph over it.
 * `DailyForecastGraphRenderer` paints an empty day list as a blank bitmap, so an empty paint is
 * never an improvement on whatever is on screen: the previous site's render, the interstitial,
 * or the fallback.
 */
@Category(ShortDuration::class)
class WidgetPaintCoordinatorEmptyDataGuardTest {

    private val context: Context = mockk(relaxed = true)
    private val appLogDao: AppLogDao = mockk(relaxed = true)

    private val coordinator = WidgetPaintCoordinator(
        context = context,
        weatherRepository = mockk<WeatherRepository>(relaxed = true),
        widgetStateManager = mockk(relaxed = true),
        appLogDao = appLogDao,
        hourlyForecastLoader = mockk(relaxed = true),
        dataBundleLoader = mockk(relaxed = true),
        gpsResampler = mockk(relaxed = true),
    )

    @Test
    fun `no daily and no hourly rows skips the paint before touching the system`() = runBlocking {
        coordinator.updateAllWidgets(
            weatherList = emptyList(),
            forecastSnapshots = emptyMap(),
            hourlyForecasts = emptyList(),
        )

        // `AppLogDao.log` is an extension that ends in `insert`; that is the call a mock sees.
        coVerify(exactly = 1) {
            appLogDao.insert(
                match<AppLogEntity> { it.tag == "WIDGET_PAINT_SKIP" && it.message.contains("reason=empty_data") },
            )
        }
        // The screen-interactive check is the first system call on the paint path; not reaching it
        // proves the guard ran ahead of everything that could push a view.
        verify(exactly = 0) { context.getSystemService(any<String>()) }
    }
}
