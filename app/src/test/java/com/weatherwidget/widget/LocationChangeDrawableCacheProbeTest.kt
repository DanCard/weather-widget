package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.MediumDuration
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The location-change cache probe against a real Room database, through the real
 * [HourlyForecastLoader] the render uses.
 *
 * 2026-09-29, Pixel 7 Pro: "Use precise device location" moved Warsaw's centre to Wola
 * (52.2333, 20.9702). The daily query's proximity box matched a row for today at Okęcie
 * (52.171, 20.973, ~7 km south) — from a fetch on 09-14, whose 16-day forecast reached today. The
 * cache was "adopted" (banner cleared) but the hourly loader, which is what the graph draws,
 * stitched 0 rows there: `hourlyRows=0` for the 31 s Wola's own fetch took. Only a real database
 * exposes this: the defect was two queries disagreeing about which rows belong to a site.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class LocationChangeDrawableCacheProbeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val centre = 52.2334 to 21.0711
    private val wola = 52.2333 to 20.9702
    private val okecie = 52.171 to 20.973
    private val kyiv = 50.4495 to 30.4910
    private val sources = listOf(WeatherSource.OPEN_METEO.id, WeatherSource.GENERIC_GAP.id)
    private lateinit var coordinator: WidgetPaintCoordinator

    @Before
    fun setUp() = runBlocking {
        val stateManager = WidgetStateManager(context)
        coordinator = WidgetPaintCoordinator(
            context = context,
            weatherRepository = mockk(relaxed = true),
            widgetStateManager = stateManager,
            appLogDao = mockk(relaxed = true),
            hourlyForecastLoader = HourlyForecastLoader(context, stateManager),
            dataBundleLoader = mockk(relaxed = true),
            gpsResampler = mockk(relaxed = true),
        )
        val db = WeatherDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        val fifteenDaysAgo = now - 15 * 86_400_000L
        val todayUtc = LocalDate.now().toEpochDay() * WidgetConstants.MS_IN_A_DAY
        // Fresh, complete cache at the centre; a 15-day-old 16-day forecast at Okęcie that still
        // has rows dated today — the phone's exact layout.
        val fiveDaysAgo = now - 5 * 86_400_000L
        // Kyiv: a complete cache (daily + hourly for today, exact site) fetched 5 days ago.
        for ((site, fetchedAt) in listOf(centre to now, okecie to fifteenDaysAgo, kyiv to fiveDaysAgo)) {
            db.forecastDao().insertAll(
                listOf(
                    ForecastEntity(
                        targetDate = todayUtc,
                        dateOfPrediction = fetchedAt,
                        highTemp = 60f,
                        lowTemp = 45f,
                        condition = "Clear",
                        source = WeatherSource.OPEN_METEO.id,
                        locationLat = site.first,
                        locationLon = site.second,
                        fetchedAt = fetchedAt,
                        batchFetchedAt = fetchedAt,
                    ),
                ),
            )
            db.hourlyForecastDao().insertAll(
                (0 until 6).map { h ->
                    HourlyForecastEntity(
                        dateTime = now - now % 3_600_000L + h * 3_600_000L,
                        locationLat = site.first,
                        locationLon = site.second,
                        temperature = 55f,
                        condition = "Clear",
                        source = WeatherSource.OPEN_METEO.id,
                        fetchedAt = fetchedAt,
                    )
                },
            )
        }
    }

    @After
    fun tearDown() {
        WeatherDatabase.resetInstanceForTesting()
    }

    @Test
    fun `the cached site itself is drawable`() = runBlocking {
        // Presence case: without it the absence below would pass against a probe that never says yes.
        assertTrue(coordinator.hasDrawableCacheAt(centre.first, centre.second, sources))
    }

    @Test
    fun `a precise fix 7 km from the cached centre is not drawable`() = runBlocking {
        assertFalse(coordinator.hasDrawableCacheAt(wola.first, wola.second, sources))
    }

    @Test
    fun `a complete but five-day-old cache is not adopted`() = runBlocking {
        // Kyiv 2026-09-29: cache from 09-24 adopted with no banner, then replaced by fresh data.
        assertFalse(coordinator.hasDrawableCacheAt(kyiv.first, kyiv.second, sources))
    }
}
