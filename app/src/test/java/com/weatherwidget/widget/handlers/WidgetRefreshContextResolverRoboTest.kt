package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.ui.ConfigActivity
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.ActiveLocationResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
class WidgetRefreshContextResolverRoboTest : RobolectricTest() {

    @Test
    fun `canonical app location scopes row and successful-check freshness`() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetWidgetId = 202
        val otherLat = 47.6062
        val otherLon = -122.3321
        val targetLat = 37.4219
        val targetLon = -122.0840
        SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
            .edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}101", otherLat.toFloat())
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}101", otherLon.toFloat())
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$targetWidgetId", targetLat.toFloat())
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$targetWidgetId", targetLon.toFloat())
            .commit()
        ActiveLocationResolver.persist(context, targetLat, targetLon)

        val forecastDao = mockk<ForecastDao>()
        val targetRow = forecast(targetLat, targetLon, fetchedAt = 1_000L)
        coEvery {
            forecastDao.getLatestForecastBySource(
                WeatherSource.NWS.id,
                any(),
                any(),
            )
        } returns targetRow
        val database = mockk<WeatherDatabase>()
        every { database.forecastDao() } returns forecastDao
        val resolver =
            WidgetRefreshContextResolver(
                databaseProvider = { database },
                sourceSuccessAt = { _, _, lat, lon ->
                    if (
                        kotlin.math.abs(lat - targetLat) < 0.0001 &&
                        kotlin.math.abs(lon - targetLon) < 0.0001
                    ) {
                        20_000L
                    } else {
                        99_000L
                    }
                },
            )

        val resolved = resolver.resolve(context, targetWidgetId)!!

        assertEquals(targetLat, resolved.location.lat, 0.0001)
        assertEquals(targetLon, resolved.location.lon, 0.0001)
        assertEquals(20_000L, resolved.latestSuccessfulOrContentAtMs)
        coVerify(exactly = 1) {
            forecastDao.getLatestForecastBySource(
                WeatherSource.NWS.id,
                match { kotlin.math.abs(it - targetLat) < 0.0001 },
                match { kotlin.math.abs(it - targetLon) < 0.0001 },
            )
        }
    }

    /**
     * With no location anywhere, there is nothing to build a refresh context around. The caller must
     * see null and abort rather than receive a context pointing at a fabricated coordinate.
     */
    @Test
    fun `resolve returns null when no location is available`() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActiveLocationResolver.clearForTesting(context)
        SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME).edit().clear().commit()
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit().clear().commit()

        val forecastDao = mockk<ForecastDao>()
        coEvery { forecastDao.getLatestWeather() } returns null
        val database = mockk<WeatherDatabase>()
        every { database.forecastDao() } returns forecastDao
        val resolver = WidgetRefreshContextResolver(
            databaseProvider = { database },
            sourceSuccessAt = { _, _, _, _ -> 0L },
        )

        assertNull(resolver.resolve(context, 303))
    }

    private fun forecast(lat: Double, lon: Double, fetchedAt: Long) =
        ForecastEntity(
            targetDate = 0L,
            dateOfPrediction = 0L,
            locationLat = lat,
            locationLon = lon,
            highTemp = 70f,
            lowTemp = 50f,
            condition = "Clear",
            source = WeatherSource.NWS.id,
            fetchedAt = fetchedAt,
        )
}
