package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.shared.actuals.TodayColumnOverlayContentResolver
import com.weatherwidget.shared.graph.HourDataAssembler
import com.weatherwidget.shared.parity.ForecastDeltaIncident
import com.weatherwidget.test.category.MediumDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Android twin of `DesktopForecastDeltaViewsIntegrationTest`: the same real rows through
 * `CurrentTempRun`, then the daily overlay resolver and the hourly graph renderer. Both must print the
 * same literal as desktop, so a platform cannot drift alone
 * (plans/261030-one-forecast-delta-all-views.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(MediumDuration::class)
class ForecastDeltaViewsIntegrationRoboTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val incident = ForecastDeltaIncident()
    private val zone: ZoneId = incident.zone
    private val source = ForecastDeltaIncident.SOURCE
    private val lat = ForecastDeltaIncident.LAT
    private val lon = ForecastDeltaIncident.LON
    private val now: LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(incident.nowMs), zone)

    @Test
    fun `the daily overlay and the hourly graph print the same resolved delta`() {
        val hourly = incident.stitched
        val observations = incident.observations
        val run = CurrentTempRun.run(hourly, observations, source, lat, lon, incident.nowMs, personalStationWeight = 1.0)
        assertEquals(ForecastDeltaIncident.DELTA, run.appliedDelta!!, 0.01f)

        val overlay = TodayColumnOverlayContentResolver.resolveLatest(
            observations = observations,
            hourlyForecasts = hourly,
            displaySourceId = source.id,
            userLat = lat,
            userLon = lon,
            nowMs = incident.nowMs,
            personalStationWeight = 1.0,
            useCelsius = false,
            forecastDelta = run.appliedDelta,
        )
        assertEquals(ForecastDeltaIncident.DELTA_TEXT, overlay?.deltaValueText)

        val series = ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = hourly,
            observations = observations,
            centerTime = now,
            displaySourceId = source.id,
            userLat = lat,
            userLon = lon,
            backHours = 4,
            forwardHours = 4,
            contextLookbackHours = 24,
            contextLookaheadHours = 8,
            now = now,
            zoneId = zone,
        )
        var label: ForecastDeltaDebug? = null
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = HourDataAssembler.assembleHourData(series, zone),
            widthPx = 900,
            heightPx = 400,
            currentTime = now,
            appliedDelta = run.appliedDelta,
            observedAt = run.observedAt,
            lastObservedTemp = run.observedTemp,
            useCelsius = false,
            onForecastDeltaPlaced = { label = it },
        )
        val placed = requireNotNull(label) { "the graph must report its delta label" }
        assertEquals("label must be drawn: $placed", "drawn", placed.reason)
        assertEquals(ForecastDeltaIncident.DELTA_TEXT, placed.valueText)
    }
}
