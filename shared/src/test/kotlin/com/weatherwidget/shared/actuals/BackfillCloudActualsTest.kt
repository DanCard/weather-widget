package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Cloud actuals reaching `observations` through the same backfill that carries temperature.
 *
 * Cloud and temperature share the same provider timestamp. Neither is delayed after the provider
 * has published it, so the current 15-minute row can extend both actual curves together.
 */
@Category(ShortDuration::class)
class BackfillCloudActualsTest {

    private val hour = 3_600_000L
    private val now = 1_755_720_000_000L
    private val source = WeatherSource.OPEN_METEO.id

    private fun hourly(offsetHours: Long, low: Int?, total: Int? = 100) = HourlyForecast(
        dateTime = now + offsetHours * hour,
        temperature = 60f,
        condition = "Cloudy",
        cloudCover = total,
        cloudCoverLow = low,
    )

    private fun build(hours: List<HourlyForecast>) =
        HistoricalActualsBackfill.build(hours, 37.417, -122.089, source, now)

    @Test
    fun `historical rows carry cloud`() {
        val rows = build(listOf(hourly(-5, 8), hourly(-4, 13)))

        assertEquals(2, rows.size)
        assertEquals(listOf(8, 13), rows.map { it.cloudCoverLow })
        assertEquals(listOf(100, 100), rows.map { it.cloudCover })
    }

    @Test
    fun `a provider-published in-progress timestamp keeps cloud and temperature`() {
        val midHour = now + 20 * 60_000L
        val rows = HistoricalActualsBackfill.build(
            listOf(hourly(0, 6)), 37.417, -122.089, source, midHour,
        )

        assertEquals(1, rows.size)
        assertEquals(6, rows[0].cloudCoverLow)
        assertEquals(100, rows[0].cloudCover)
        assertEquals(60f, rows[0].temperature, 0.001f)
    }

    /**
     * The 6-vs-86 case, now deliberately accepted. An hour carries cloud the moment it ends, even
     * though Open-Meteo may revise it ~40 minutes later. The revision is picked up because
     * `observations` is keyed on `(stationId, timestamp)` and the next fetch REPLACEs in place;
     * withholding it instead left the curve permanently undrawable at the widget's zoom.
     */
    @Test
    fun `an hour carries cloud as soon as it ends`() {
        val justEnded = now // the -1 hour ends exactly here
        val rows = HistoricalActualsBackfill.build(
            listOf(hourly(-1, 6)), 37.417, -122.089, source, justEnded,
        )

        assertEquals(6, rows.single().cloudCoverLow)
    }

    /** A missing low value stays missing — never an observation of a clear sky nobody made. */
    @Test
    fun `hours without a low value carry null, not zero`() {
        val rows = build(listOf(hourly(-5, low = null, total = 99)))

        assertNull(rows.single().cloudCoverLow)
        assertEquals(99, rows.single().cloudCover)
    }

    /**
     * `observations` is keyed on (stationId, timestamp), so a sub-hourly series lands without
     * collision — the property that lets 15-minute cloud use this path at all.
     */
    @Test
    fun `sub-hourly timestamps produce distinct rows under one station`() {
        val q = 15 * 60_000L
        val base = now - 4 * hour
        val quarters = listOf(0L, q, 2 * q, 3 * q).mapIndexed { i, off ->
            HourlyForecast(
                dateTime = base + off,
                temperature = 60f,
                condition = "Cloudy",
                cloudCoverLow = 10 * (i + 1),
            )
        }
        val rows = build(quarters)

        assertEquals(4, rows.size)
        assertEquals(4, rows.map { it.timestamp }.toSet().size)
        assertEquals(1, rows.map { it.stationId }.toSet().size)
        assertEquals(listOf(10, 20, 30, 40), rows.map { it.cloudCoverLow })
    }

    /**
     * These rows are model output filed at distanceKm=0, which has already outranked real stations
     * once in the temperature blend. Any future cloud blend must exclude them via the same
     * classifier, so pin the classification here.
     */
    @Test
    fun `cloud-carrying backfill rows remain classified as synthetic`() {
        val row = build(listOf(hourly(-5, 8))).single()

        assertTrue(
            "a cloud blend must be able to rank this below real stations",
            ObservationSourceMatcherAccess.isSynthetic(row.stationId, source),
        )
        assertEquals(0f, row.distanceKm, 0.001f)
        assertNotNull(row.cloudCoverLow)
    }

    @Test
    fun `silurian past forecast never becomes an observation row`() {
        val rows = HistoricalActualsBackfill.build(
            hourly = listOf(hourly(-1, low = 28, total = 56)),
            latitude = 37.417,
            longitude = -122.089,
            sourceId = WeatherSource.SILURIAN.id,
            nowMs = now,
        )

        assertTrue(rows.isEmpty())
    }
}

private object ObservationSourceMatcherAccess {
    fun isSynthetic(stationId: String, sourceId: String) =
        com.weatherwidget.shared.observations.ObservationSourceMatcher
            .isSyntheticBackfillStation(stationId, sourceId)
}
