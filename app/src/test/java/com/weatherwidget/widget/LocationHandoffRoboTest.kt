package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.ui.ConfigActivity
import com.weatherwidget.ui.LocationUpdater
import com.weatherwidget.util.SharedPreferencesUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Category(LongDuration::class)
class LocationHandoffRoboTest : RobolectricTest() {

    private lateinit var context: Context
    private val widgetId = 271
    private val home = HandoffLocation(37.4168, -122.0890, "Home")
    private val away = HandoffLocation(37.3774, -122.0749, "Away")
    private val nowMs = Instant.parse("2026-07-24T19:00:00Z").toEpochMilli()
    private val source = WeatherSource.NWS.id

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME).edit().clear().commit()
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit().clear().commit()
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(widgetId, info)
        writeActive(home)
    }

    @Test
    fun `home away home keeps prior location until candidate body is useful`() {
        assertEquals(
            CandidateProposal.UPDATED,
            LocationUpdater.proposeFollowDeviceLocation(
                context,
                away.lat,
                away.lon,
                away.label,
                enqueueRefresh = false,
                nowMs = nowMs,
                ids = intArrayOf(widgetId),
            ),
        )
        val awayCandidate = LocationHandoffStore.getCandidate(context)!!

        val sparseAway = evaluateCandidateUsability(
            forecasts = daily(away),
            hourlyForecasts = hourly(away, 0..12),
            requiredSourceIds = setOf(source),
            requiresHourlyData = true,
            nowMs = nowMs,
            candidateFirstSeenMs = awayCandidate.firstSeenMs,
        )

        assertFalse(sparseAway.useful)
        assertActive(home)

        val completeAway = evaluateCandidateUsability(
            forecasts = daily(away),
            hourlyForecasts = hourly(away, -12..12),
            requiredSourceIds = setOf(source),
            requiresHourlyData = true,
            nowMs = nowMs,
            candidateFirstSeenMs = awayCandidate.firstSeenMs,
        )
        assertTrue(completeAway.useful)
        assertTrue(LocationUpdater.promoteCandidateIfMatches(context, awayCandidate, intArrayOf(widgetId)))
        assertActive(away)

        assertEquals(
            CandidateProposal.UPDATED,
            LocationUpdater.proposeFollowDeviceLocation(
                context,
                home.lat,
                home.lon,
                home.label,
                enqueueRefresh = false,
                nowMs = nowMs + 1_000L,
                ids = intArrayOf(widgetId),
            ),
        )
        val homeCandidate = LocationHandoffStore.getCandidate(context)!!
        val cachedHome = evaluateCandidateUsability(
            forecasts = daily(home),
            hourlyForecasts = hourly(home, -12..12),
            requiredSourceIds = setOf(source),
            requiresHourlyData = true,
            nowMs = nowMs + 1_000L,
            candidateFirstSeenMs = homeCandidate.firstSeenMs,
        )
        assertTrue(cachedHome.useful)
        assertTrue(LocationUpdater.promoteCandidateIfMatches(context, homeCandidate, intArrayOf(widgetId)))
        assertActive(home)
        assertNull(LocationHandoffStore.getCandidate(context))
    }

    private fun writeActive(location: HandoffLocation) {
        SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME).edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", location.lat.toFloat())
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", location.lon.toFloat())
            .commit()
    }

    private fun assertActive(location: HandoffLocation) {
        val active = WidgetStateManager(context).getWidgetLocation(widgetId)!!
        assertEquals(location.lat, active.first, 0.0001)
        assertEquals(location.lon, active.second, 0.0001)
    }

    private fun daily(location: HandoffLocation): List<ForecastEntity> {
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault())
        return (0..2).map { offset ->
            ForecastEntity(
                targetDate = today.plusDays(offset.toLong()).toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                dateOfPrediction = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                locationLat = location.lat,
                locationLon = location.lon,
                highTemp = 76f,
                lowTemp = 56f,
                condition = "Clear",
                source = source,
            )
        }
    }

    private fun hourly(location: HandoffLocation, offsets: IntRange): List<HourlyForecastEntity> =
        offsets.map { offset ->
            HourlyForecastEntity(
                dateTime = nowMs + offset * 60 * 60 * 1000L,
                locationLat = location.lat,
                locationLon = location.lon,
                temperature = 66f,
                condition = "Clear",
                source = source,
                fetchedAt = nowMs,
            )
        }
}
