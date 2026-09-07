package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import org.junit.experimental.categories.Category

/**
 * Regression for the 2026-07-13 Samsung divergence: the daily bar said yesterday's rain chance was
 * 9% while the hourly graph, drawn from the same database, said 5%.
 *
 * The freeze path read RAW proximity-box hourly rows. GPS jitter had split the site into several
 * coordinate fragments, all inside the ~7mi box, and the day/night reducer is a `max` — so a
 * neighbouring fragment's 9% beat the real site's 4% straight into the archive.
 *
 * Coordinates and values below are the real ones from that device.
 */
@Category(ShortDuration::class)
class DailyRainChanceSiteSelectionTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val date = LocalDate.of(2026, 7, 13)

    // The device's actual site.
    private val siteLat = 37.417
    private val siteLon = -122.089

    private fun hour(
        hourOfDay: Int,
        chance: Int,
        lat: Double,
        lon: Double,
        fetchedAt: Long = 1_000L,
        onDate: LocalDate = date,
    ) = HourlyForecast(
        dateTime = onDate.atTime(hourOfDay, 0).atZone(zone).toInstant().toEpochMilli(),
        temperature = 70f,
        condition = "Clear",
        precipProbability = chance,
        source = "NWS",
        fetchedAt = fetchedAt,
        locationLat = lat,
        locationLon = lon,
    )

    /** Real site says 4%; two jitter fragments inside the box say 9% and 13%. */
    private val boxRows = listOf(
        hour(10, 4, siteLat, siteLon),
        hour(14, 3, siteLat, siteLon),
        hour(10, 9, 37.424, -122.088),   // jitter fragment from an older fetch coordinate
        hour(14, 13, 37.422, -122.087),  // another
    )

    @Test
    fun `raw box rows let a neighbouring fragment win — the bug`() {
        val poisoned = DailyRainLabels.calculateDayNightPrecipProbabilities(
            hourly = boxRows,
            targetDate = date,
            displaySourceId = "NWS",
            zoneId = zone,
        )
        assertEquals("max over the whole box picks the worst fragment", 13, poisoned.dayMax)
    }

    @Test
    fun `site-resolved chance comes from the user's own coordinates`() {
        val resolved = DailyRainLabels.resolveLiveDayNightChanceAtSite(
            displaySourceId = "NWS",
            daytimePrecipProbability = 9, // the NWS period field — must NOT be reached; hourly wins
            nighttimePrecipProbability = 3,
            precipProbability = 9,
            hourly = boxRows,
            centerLat = siteLat,
            centerLon = siteLon,
            targetDate = date,
            zoneId = zone,
        )
        assertEquals(4, resolved.dayPrecip)
    }

    /**
     * The freeze must agree with what the hourly graph draws — that identity is the whole bug. Both
     * now go through the same site selector, so this asserts they cannot diverge again.
     */
    @Test
    fun `freeze and graph resolve the same rows`() {
        val graphRows = DailyRainLabels.selectSiteHourly(boxRows, "NWS", siteLat, siteLon)
        val graphMax = graphRows.mapNotNull { it.precipProbability }.maxOrNull()

        val frozen = DailyRainLabels.resolveLiveDayNightChanceAtSite(
            displaySourceId = "NWS",
            daytimePrecipProbability = 9,
            nighttimePrecipProbability = 3,
            precipProbability = 9,
            hourly = boxRows,
            centerLat = siteLat,
            centerLon = siteLon,
            targetDate = date,
            zoneId = zone,
        )
        assertEquals(graphMax, frozen.dayPrecip)
    }

    /** Fresher rows at the same site still win — site selection must not defeat freshest-wins. */
    @Test
    fun `freshest row at the site wins`() {
        val rows = listOf(
            hour(10, 4, siteLat, siteLon, fetchedAt = 1_000L),
            hour(10, 7, siteLat, siteLon, fetchedAt = 9_000L), // later fetch revised it up
        )
        val resolved = DailyRainLabels.resolveLiveDayNightChanceAtSite(
            displaySourceId = "NWS",
            daytimePrecipProbability = null,
            nighttimePrecipProbability = null,
            precipProbability = null,
            hourly = rows,
            centerLat = siteLat,
            centerLon = siteLon,
            targetDate = date,
            zoneId = zone,
        )
        assertEquals(7, resolved.dayPrecip)
    }

    @Test
    fun `daily display ignores older 21 percent coordinate fragment`() {
        val tuesday = LocalDate.of(2026, 9, 8)
        val centerLat = 37.416805
        val centerLon = -122.088951
        val rows = listOf(
            // Fresh NWS rows at the current coordinate fragment: 1% Tuesday, 11% overnight.
            hour(10, 1, 37.417, -122.089, fetchedAt = 10_000L, onDate = tuesday),
            hour(20, 1, 37.417, -122.089, fetchedAt = 10_000L, onDate = tuesday),
            hour(5, 11, 37.417, -122.089, fetchedAt = 10_000L, onDate = tuesday.plusDays(1)),
            // Older rows at the stale fragment that produced the Samsung's 21% / 21% labels.
            hour(10, 21, 37.415, -122.087, fetchedAt = 1_000L, onDate = tuesday),
            hour(20, 21, 37.415, -122.087, fetchedAt = 1_000L, onDate = tuesday),
            hour(5, 21, 37.415, -122.087, fetchedAt = 1_000L, onDate = tuesday.plusDays(1)),
        )

        val raw = DailyRainLabels.resolveDailyLabelPrecip(
            isPast = false,
            displaySourceId = "NWS",
            daytimePrecipProbability = null,
            nighttimePrecipProbability = null,
            precipProbability = null,
            hourly = rows,
            targetDate = tuesday,
            zoneId = zone,
        )
        assertEquals(21, raw.dayPrecip)
        assertEquals(21, raw.nightPrecip)

        val sited = DailyRainLabels.resolveDailyLabelPrecipAtSite(
            isPast = false,
            displaySourceId = "NWS",
            daytimePrecipProbability = null,
            nighttimePrecipProbability = null,
            precipProbability = null,
            hourly = rows.reversed(),
            centerLat = centerLat,
            centerLon = centerLon,
            targetDate = tuesday,
            zoneId = zone,
        )
        assertEquals(1, sited.dayPrecip)
        assertEquals(11, sited.nightPrecip)
    }

    /**
     * The repair reconstructs what the site's rows said WHILE the day was live: snapshots fetched
     * after the 8pm window close are hindsight and must not move the archived value.
     */
    @Test
    fun `repair ignores snapshots taken after the window closed`() {
        val duringDay = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val afterClose = date.atTime(23, 0).atZone(zone).toInstant().toEpochMilli()

        val history = listOf(
            hour(10, 4, siteLat, siteLon, fetchedAt = duringDay),
            hour(10, 60, siteLat, siteLon, fetchedAt = afterClose), // hindcast revision
            hour(10, 9, 37.424, -122.088, fetchedAt = duringDay),   // jitter fragment
        )

        val rederived = FrozenRainChanceRepair.rederive(
            history = history,
            displaySourceId = "NWS",
            centerLat = siteLat,
            centerLon = siteLon,
            date = date,
            zoneId = zone,
        )
        assertEquals("the site's in-window value, not the hindcast and not the fragment", 4, rederived.dayPrecip)
    }
}
