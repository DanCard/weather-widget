package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.shared.util.WeatherSourceOrdering
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [ActualsAggregator] must not emit a daily-history group for a feed the user cannot select.
 *
 * Such a row is only ever written, retained a month and recomputed: every reader selects by display
 * source — `DailyActualsStore` filters `it.source in activeSources`, `ForecastHistoryActivity`
 * matches `requestedSource`, and `ActualsBaselineResolver` walks `orderedVisibleSources` alone.
 *
 * The filter used to name METAR specifically while its comment described the whole category, so
 * SYNOPTIC kept getting rows (measured 2026-09-06 on the reporting device: METAR 0, SYNOPTIC 51),
 * and SYNOPTIC's group is the most expensive in the aggregator — 34,726 observations in one 132h
 * window against NWS's 7,862, duplicating the blend that Silurian's borrowed group already runs over
 * exactly those rows.
 *
 * The load-bearing assertion here is the last one: a borrower configured onto a provider must still
 * get its curve, because `borrowedGroups` is built from `byApi` BEFORE the filter. Applying the
 * filter first would silently blank Silurian on any device with `actuals_provider_SILURIAN = SYNOPTIC`.
 */
@Category(ShortDuration::class)
class ActualsNonDisplayableSourceTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.parse("2026-06-03")
    private val lat = 37.4168
    private val lon = -122.0890

    @After
    fun tearDown() = ActualsProviderResolver.resetPreferenceSource()

    private fun observation(
        stationId: String,
        time: String,
        temperature: Float,
        api: String,
        distanceKm: Float = 2f,
    ) = ObservationReading(
        stationId = stationId,
        stationName = stationId,
        timestamp = LocalDateTime.parse(time).atZone(zone).toInstant().toEpochMilli(),
        temperature = temperature,
        condition = "observed",
        locationLat = lat,
        locationLon = lon,
        distanceKm = distanceKm,
        api = api,
        stationType = "OFFICIAL",
    )

    /** A full day of readings for one api, dense enough for the blend to emit a range. */
    private fun dayOf(api: String, stationId: String, base: Float): List<ObservationReading> =
        (6..20).map { hour ->
            observation(
                stationId = stationId,
                time = "2026-06-03T%02d:00:00".format(hour),
                temperature = base + hour,
                api = api,
            )
        }

    private fun aggregate(observations: List<ObservationReading>) =
        ActualsAggregator.aggregate(
            observations = observations,
            hourlyForecasts = emptyList(),
            locationLat = lat,
            locationLon = lon,
            zoneId = zone,
            updatedAtMs = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )

    @Test
    fun `SYNOPTIC never gets a group of its own`() {
        val rows = aggregate(dayOf(WeatherSource.NWS.id, "KNUQ", 40f) + dayOf(WeatherSource.SYNOPTIC.id, "G4110", 41f))
        assertTrue(
            "SYNOPTIC is absent from ALL_CONFIGURABLE, so no reader could ever select its row",
            rows.none { it.source == WeatherSource.SYNOPTIC.id },
        )
        assertNotNull("NWS is displayable and must keep its row", rows.find { it.source == WeatherSource.NWS.id })
    }

    @Test
    fun `METAR still gets no group - the existing behaviour is unchanged`() {
        val rows = aggregate(dayOf(WeatherSource.NWS.id, "KNUQ", 40f) + dayOf(WeatherSource.METAR.id, "KSJC", 41f))
        assertTrue(rows.none { it.source == WeatherSource.METAR.id })
    }

    @Test
    fun `VISUAL_CROSSING gets no group either`() {
        // Chosen deliberately over the narrower provider-only rule: it is not in ALL_CONFIGURABLE,
        // so it cannot be picked in Settings, and its historicalDataKind defaults to NONE, so
        // ActualsBaselineResolver.hasNativeActuals excludes it from ever being an accuracy baseline.
        // Nothing can read its row.
        val rows = aggregate(
            dayOf(WeatherSource.NWS.id, "KNUQ", 40f) +
                dayOf(WeatherSource.VISUAL_CROSSING.id, "VISUAL_CROSSING_1", 41f),
        )
        assertTrue(rows.none { it.source == WeatherSource.VISUAL_CROSSING.id })
    }

    @Test
    fun `every emitted source is one the user can select`() {
        val rows = aggregate(
            dayOf(WeatherSource.NWS.id, "KNUQ", 40f) +
                dayOf(WeatherSource.SYNOPTIC.id, "G4110", 41f) +
                dayOf(WeatherSource.METAR.id, "KSJC", 42f) +
                dayOf(WeatherSource.VISUAL_CROSSING.id, "VC1", 43f),
        )
        val configurable = WeatherSourceOrdering.ALL_CONFIGURABLE.map { it.id }.toSet()
        assertEquals(emptyList<String>(), rows.map { it.source }.distinct().filterNot { it in configurable })
    }

    @Test
    fun `a borrower still receives its provider's rows after the provider group is dropped`() {
        // THE regression this filter could cause. Silurian has no actuals of its own; pointed at
        // Synoptic it must still produce a curve, built from the very rows whose own group is gone.
        ActualsProviderResolver.installPreferenceSource { source ->
            if (source == WeatherSource.SILURIAN) WeatherSource.SYNOPTIC else null
        }

        val synoptic = dayOf(WeatherSource.SYNOPTIC.id, "G4110", 41f)
        val rows = aggregate(dayOf(WeatherSource.NWS.id, "KNUQ", 40f) + synoptic)

        val silurian = rows.find { it.source == WeatherSource.SILURIAN.id }
        assertNotNull("Silurian borrows Synoptic and must still get a row", silurian)
        assertTrue(rows.none { it.source == WeatherSource.SYNOPTIC.id })

        // And the borrowed values are the Synoptic observations', not something degraded.
        assertNotNull(silurian!!.computedHighTemp)
        assertNotNull(silurian.computedLowTemp)
    }
}
