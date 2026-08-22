package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import org.junit.experimental.categories.Category

/**
 * Guards the daily-bar vs hourly-graph convergence against WINDOW dependence — the recurring class of
 * bug where the two views show different actual highs/lows for the same day because they blend the
 * observation series over different-width windows.
 *
 * Field case (2026-07-08): the daily column showed a low of 52.6 while the hourly graph bottomed at
 * ~54.4 for the same NWS day. Both call [ActualTemperatureSeriesBuilder.blendObservationSeries], but
 * [ActualsAggregator] used to blend each calendar day in ISOLATION (window == the day), so a near
 * station whose readings only bracket the day's first cold candidate ACROSS midnight was dropped, and
 * a lone cold outlier dominated the low. The graph's multi-day render window kept that coverage.
 *
 * The invariant these tests pin: the daily aggregate for a day must equal the same day's extrema from
 * a wide-window blend — i.e. the daily value must be independent of whether neighbouring-day
 * observations are present. See ActualsAggregator.DAILY_BLEND_CONTEXT_MS and
 * daily_vs_hourly_actual_extrema_mismatch. Complements [ActualsLoneStationGuardTest], which only ever
 * feeds single-day observations and so never exercised the cross-midnight window.
 */
@Category(ShortDuration::class)
class ActualsWindowIndependenceTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val dayMs = 24 * 3600_000L

    // Day D (target) and its edge/daytime coverage. NEAR is the dominant official station but its
    // first IN-DAY reading is 01:00 — it can only bracket the 00:15 cold candidate using its previous
    // evening (D-1 23:30) reading, which lives outside a day-isolated window.
    private val dPreviousEveningNear = observation("NEAR", "2026-06-02T23:30:00", 53f, distanceKm = 2.2f)

    private fun dayDNear(): List<ObservationReading> {
        // 01:00 is the in-day floor (54°F); temps rise to a midday peak and fall back in the evening.
        val temps = listOf(54f, 54f, 55f, 57f, 61f, 66f, 71f, 74f, 75f, 74f, 70f, 66f, 62f, 60f, 58f, 57f)
        return temps.mapIndexed { i, t ->
            observation("NEAR", "2026-06-03T%02d:00:00".format(1 + i), t, distanceKm = 2.2f)
        }
    }

    // A cold reading at 00:15 from a moderately distant OFFICIAL station with no other coverage of its
    // own. Sole at 00:15 in the day-isolated window (NEAR cannot reach backward) → suppressed by the
    // lone-station guard; corroborated by NEAR in the wide window → blended in (pulls the low down).
    private val dEdgeCold = observation("EDGE", "2026-06-03T00:15:00", 44f, distanceKm = 4f)

    private fun forecasts(): List<HourlyForecast> {
        val start = LocalDateTime.parse("2026-06-02T00:00:00")
        return (0..48).map { i ->
            HourlyForecast(
                dateTime = start.plusHours(i.toLong()).atZone(zone).toInstant().toEpochMilli(),
                temperature = 60f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
            )
        }
    }

    @Test
    fun `daily aggregate low equals the wide-window graph low, not the day-isolated low`() {
        val allObs = listOf(dPreviousEveningNear, dEdgeCold) + dayDNear()
        val dayOnlyObs = allObs.filter { it.timestamp >= epoch("2026-06-03T00:00:00") }
        val forecasts = forecasts()

        val dStart = epoch("2026-06-03T00:00:00")
        val dEnd = epoch("2026-06-04T00:00:00")

        // Day-isolated blend (the OLD behaviour): NEAR's evening reading is excluded, EDGE stands
        // alone at 00:15 and is suppressed, so the low is NEAR's 54°F in-day floor.
        val isolatedLow = blendedDayLow(dayOnlyObs, forecasts, dStart, dEnd, dStart, dEnd)

        // Wide-window blend (what the hourly graph does): NEAR brackets 00:15 across midnight, EDGE is
        // corroborated and blended, pulling the low below the in-day floor.
        val wideLow = blendedDayLow(allObs, forecasts, dStart - dayMs, dEnd + dayMs, dStart, dEnd)

        // The daily aggregate must land on the wide-window value regardless of the day boundary.
        val aggregateLow = ActualsAggregator.aggregate(
            observations = allObs,
            hourlyForecasts = forecasts,
            locationLat = LAT,
            locationLon = LON,
            zoneId = zone,
        ).single { it.source == WeatherSource.NWS.id && it.date == dayEpochKey() }.computedLowTemp

        // The scenario must actually be window-dependent, or the equality below proves nothing. The
        // gap is modest because a distant personal outlier carries little IDW weight, but it is well
        // above the 0.05° equality tolerance, so a reverted widen (aggregate -> isolated) is caught.
        assertTrue(
            "scenario must be window-dependent (isolated=$isolatedLow wide=$wideLow)",
            abs(isolatedLow - wideLow!!) > 0.3f,
        )
        assertEquals("daily aggregate low must match the wide-window (graph) low", wideLow, aggregateLow!!, 0.05f)
        assertTrue(
            "daily aggregate low ($aggregateLow) must NOT regress to the day-isolated low ($isolatedLow)",
            abs(aggregateLow - isolatedLow!!) > 0.3f,
        )
    }

    @Test
    fun `daily aggregate and hourly graph build agree on both extremes for the cross-midnight day`() {
        val allObs = listOf(dPreviousEveningNear, dEdgeCold) + dayDNear()
        val forecasts = forecasts()

        val daily = ActualsAggregator.aggregate(
            observations = allObs,
            hourlyForecasts = forecasts,
            locationLat = LAT,
            locationLon = LON,
            zoneId = zone,
        ).single { it.source == WeatherSource.NWS.id && it.date == dayEpochKey() }

        val graph = ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = forecasts,
            observations = allObs,
            centerTime = LocalDateTime.parse("2026-06-03T12:00:00"),
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            backHours = 14,
            forwardHours = 12,
            contextLookbackHours = 72,
            contextLookaheadHours = 60,
            now = LocalDateTime.parse("2026-06-05T12:00:00"),
            zoneId = zone,
        )

        val graphActuals = graph.points
            .filter { it.isActual && it.actualTemp != null && onDayD(it) }
            .map { it.actualTemp!! }
        assertTrue("graph must render day-D actuals", graphActuals.isNotEmpty())
        assertEquals("daily low must equal hourly graph min", daily.computedLowTemp!!, graphActuals.min(), 0.1f)
        assertEquals("daily high must equal hourly graph max", daily.computedHighTemp!!, graphActuals.max(), 0.1f)
    }

    private fun blendedDayLow(
        obs: List<ObservationReading>,
        forecasts: List<HourlyForecast>,
        windowStart: Long,
        windowEnd: Long,
        dayStart: Long,
        dayEnd: Long,
    ): Float =
        ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = obs,
            hourlyForecasts = forecasts,
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            startMs = windowStart,
            endMs = windowEnd,
            zoneId = zone,
        ).observations
            .filter { it.timestamp in dayStart until dayEnd }
            .minOf { it.temperature }

    private fun onDayD(point: ActualTemperaturePoint): Boolean =
        java.time.Instant.ofEpochMilli(point.timeMs).atZone(zone).toLocalDate() ==
            java.time.LocalDate.parse("2026-06-03")

    private fun dayEpochKey(): Long = java.time.LocalDate.parse("2026-06-03").toEpochDay() * 86_400_000L

    private fun observation(
        stationId: String,
        time: String,
        temperature: Float,
        distanceKm: Float,
        api: String = WeatherSource.NWS.id,
        stationType: String = "OFFICIAL",
    ): ObservationReading =
        ObservationReading(
            stationId = stationId,
            stationName = stationId,
            timestamp = epoch(time),
            temperature = temperature,
            condition = "observed",
            locationLat = LAT,
            locationLon = LON,
            distanceKm = distanceKm,
            api = api,
            stationType = stationType,
        )

    private fun epoch(value: String): Long =
        LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()

    private companion object {
        const val LAT = 37.4220
        const val LON = -122.0841
    }
}
