package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.getForecastsInRange
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.ui.ConfigActivity
import com.weatherwidget.ui.LocationUpdater
import com.weatherwidget.util.SharedPreferencesUtil
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Exercises home -> away -> home against real Room site filtering and Android preferences.
 * Incomplete away data cannot replace home; cached complete home data permits an immediate return.
 */
@RunWith(AndroidJUnit4::class)
class LocationHandoffIntegrationTest : IsolatedIntegrationTest("location_handoff") {

    private val widgetId = 9_071
    private val home = HandoffLocation(37.4168, -122.0890, "Home")
    private val away = HandoffLocation(37.3774, -122.0749, "Away")
    private val nowMs = Instant.parse("2026-07-24T19:00:00Z").toEpochMilli()
    private val source = WeatherSource.NWS.id

    @Before
    fun clearPrefsBeforeTest() {
        widgetPrefs().edit().clear().commit()
        weatherPrefs().edit().clear().commit()
    }

    @After
    fun clearPrefsAfterTest() {
        widgetPrefs().edit().clear().commit()
        weatherPrefs().edit().clear().commit()
    }

    @Test
    fun homeAwayHome_retainsLastUsefulSiteUntilCandidateHasCompleteBody() = runBlocking {
        writeActive(home)
        db.forecastDao().insertAll(daily(home) + daily(away))
        db.hourlyForecastDao().insertAll(hourly(home, -12..11) + hourly(away, 0..11))

        assertEquals(
            CandidateProposal.UPDATED,
            LocationUpdater.proposeFollowDeviceLocation(
                context = context,
                lat = away.lat,
                lon = away.lon,
                label = away.label,
                enqueueRefresh = false,
                nowMs = nowMs,
                ids = intArrayOf(widgetId),
            ),
        )
        val awayCandidate = LocationHandoffStore.getCandidate(context)!!

        assertFalse(usabilityFor(away, awayCandidate).useful)
        assertActive(home)

        db.hourlyForecastDao().insertAll(hourly(away, -12..-1))
        assertTrue(usabilityFor(away, awayCandidate).useful)
        assertTrue(LocationUpdater.promoteCandidateIfMatches(context, awayCandidate, intArrayOf(widgetId)))
        assertActive(away)

        assertEquals(
            CandidateProposal.UPDATED,
            LocationUpdater.proposeFollowDeviceLocation(
                context = context,
                lat = home.lat,
                lon = home.lon,
                label = home.label,
                enqueueRefresh = false,
                nowMs = nowMs + 1_000L,
                ids = intArrayOf(widgetId),
            ),
        )
        val homeCandidate = LocationHandoffStore.getCandidate(context)!!
        assertTrue(usabilityFor(home, homeCandidate, nowMs + 1_000L).useful)
        assertTrue(LocationUpdater.promoteCandidateIfMatches(context, homeCandidate, intArrayOf(widgetId)))
        assertActive(home)
        assertNull(LocationHandoffStore.getCandidate(context))
    }

    private suspend fun usabilityFor(
        location: HandoffLocation,
        candidate: CandidateLocation,
        evaluationMs: Long = nowMs,
    ): CandidateUsability {
        val todayMs = LocalDate.ofInstant(
            Instant.ofEpochMilli(evaluationMs),
            ZoneId.systemDefault(),
        ).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val forecasts = db.forecastDao().getForecastsInRange(
            todayMs,
            todayMs + 2 * WidgetConstants.MS_IN_A_DAY,
            location.lat,
            location.lon,
        )
        val hourlyInProximityBox = db.hourlyForecastDao().getHourlyForecastsBySource(
            evaluationMs - 12 * HOUR_MS,
            evaluationMs + 12 * HOUR_MS,
            location.lat,
            location.lon,
            source,
        )
        val hourly = LocationMatch.selectNearestSite(
            rows = hourlyInProximityBox,
            lat = location.lat,
            lon = location.lon,
            latOf = HourlyForecastEntity::locationLat,
            lonOf = HourlyForecastEntity::locationLon,
        )
        return evaluateCandidateUsability(
            forecasts = forecasts,
            hourlyForecasts = hourly,
            requiredSourceIds = setOf(source),
            requiresHourlyData = true,
            nowMs = evaluationMs,
            candidateFirstSeenMs = candidate.firstSeenMs,
            // Derived exactly as production does rather than hardcoded false, so this helper stays
            // honest if a case is ever added that starts from no location.
            isAcquisition = ActiveLocationResolver.current(context) == null,
        )
    }

    private fun writeActive(location: HandoffLocation) {
        widgetPrefs().edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", location.lat.toFloat())
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", location.lon.toFloat())
            .commit()
    }

    private fun widgetPrefs() =
        SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)

    private fun weatherPrefs() =
        SharedPreferencesUtil.getPrefs(context, "weather_prefs")

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
                fetchedAt = nowMs,
            )
        }
    }

    private fun hourly(location: HandoffLocation, offsets: IntRange): List<HourlyForecastEntity> =
        offsets.map { offset ->
            HourlyForecastEntity(
                dateTime = nowMs + offset * HOUR_MS,
                locationLat = location.lat,
                locationLon = location.lon,
                temperature = 66f,
                condition = "Clear",
                source = source,
                fetchedAt = nowMs,
            )
        }

    companion object {
        private const val HOUR_MS = 60 * 60 * 1_000L
    }
}
