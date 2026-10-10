package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * The daily view's noon cloud and rain maxima, kept on the forecast row so hourly is stored to 72 h
 * only (`performance/261010-daily-view-summaries-instead-of-far-hourly.md`). A field is taken only
 * from rows that cover its whole window, and a fetch that does not cover one never blanks it.
 */
@Category(ShortDuration::class)
class DailyHourlySummariesTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val source = "OPEN_METEO"
    private val start = LocalDate.of(2026, 10, 10)

    private fun at(day: LocalDate, h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    /** Hourly rows from [from] (inclusive) to [until] (exclusive), every [stepH] hours. */
    private fun series(
        from: Long,
        until: Long,
        stepH: Int = 1,
        pop: (Long) -> Int? = { 10 },
        cloudLow: (Long) -> Int? = { null },
        cloud: (Long) -> Int? = { 40 },
        sourceId: String? = source,
    ) = generateSequence(from) { it + stepH * 3_600_000L }.takeWhile { it < until }.map {
        HourlyForecast(
            dateTime = it,
            temperature = 60f,
            condition = "Cloudy",
            precipProbability = pop(it),
            cloudCover = cloud(it),
            cloudCoverLow = cloudLow(it),
            source = sourceId,
        )
    }.toList()

    /** A 16-day download from midnight today, the shape Open-Meteo returns. */
    private val sixteenDays = series(at(start, 0), at(start.plusDays(16), 0), pop = { ms ->
        if (ms == at(start.plusDays(9), 14)) 80 else if (ms == at(start.plusDays(9), 22)) 60 else 10
    }, cloud = { ms -> if (ms == at(start.plusDays(9), 12)) 75 else 40 })

    @Test
    fun `a full download gives every full day all three values`() {
        (0 until 15).forEach { offset ->
            val s = DailyHourlySummaries.forDate(sixteenDays, start.plusDays(offset.toLong()), source, zone)
            assertEquals("day $offset noon", if (offset == 9) 75 else 40, s.noonCloudPercent)
            assertEquals("day $offset day", if (offset == 9) 80 else 10, s.dayPrecipMax)
            assertEquals("day $offset night", if (offset == 9) 60 else 10, s.nightPrecipMax)
        }
        // The last day's night runs into a day the download does not have.
        val last = DailyHourlySummaries.forDate(sixteenDays, start.plusDays(15), source, zone)
        assertEquals(40, last.noonCloudPercent)
        assertEquals(10, last.dayPrecipMax)
        assertNull(last.nightPrecipMax)
    }

    @Test
    fun `a download ending at 14 00 gives that day's noon but not its day or night`() {
        val rows = series(at(start, 0), at(start, 15))
        val s = DailyHourlySummaries.forDate(rows, start, source, zone)
        assertEquals(40, s.noonCloudPercent)
        assertNull(s.dayPrecipMax)
        assertNull(s.nightPrecipMax)
    }

    @Test
    fun `a download starting at 10 00 leaves today's day window empty`() {
        val rows = series(at(start, 10), at(start.plusDays(2), 0))
        val s = DailyHourlySummaries.forDate(rows, start, source, zone)
        assertEquals(40, s.noonCloudPercent)
        assertNull("08–10 missing", s.dayPrecipMax)
        assertEquals(10, s.nightPrecipMax)
    }

    @Test
    fun `a 3-hourly series covers the windows at its own step`() {
        // OWM's free /forecast: every 3 h. 09, 12, 15, 18 cover 08:00–20:00; 21 … 06 cover the night.
        val rows = series(at(start, 0), at(start.plusDays(2), 0), stepH = 3, pop = { ms ->
            if (ms == at(start, 15)) 55 else 5
        })
        val s = DailyHourlySummaries.forDate(rows, start, source, zone)
        assertEquals(55, s.dayPrecipMax)
        assertEquals(5, s.nightPrecipMax)
        assertEquals(40, s.noonCloudPercent)
    }

    @Test
    fun `window coverage edges`() {
        val day = at(start, 8) to at(start, 20)
        assertTrue(DailyHourlySummaries.windowCovered(series(at(start, 8), at(start, 20)), day.first, day.second))
        assertFalse("ends at 18", DailyHourlySummaries.windowCovered(series(at(start, 8), at(start, 19)), day.first, day.second))
        assertFalse("starts at 09", DailyHourlySummaries.windowCovered(series(at(start, 9), at(start, 20)), day.first, day.second))
        assertFalse(DailyHourlySummaries.windowCovered(emptyList(), day.first, day.second))
        assertFalse(
            "rows only outside the window",
            DailyHourlySummaries.windowCovered(series(at(start, 0), at(start, 6)) + series(at(start, 21), at(start, 23)), day.first, day.second),
        )
    }

    @Test
    fun `noon is the visible cloud — total, else the largest band — as DailyNoonCloudCover reads it`() {
        val withTotal = series(at(start, 0), at(start.plusDays(1), 0), cloudLow = { 15 }, cloud = { 90 })
        assertEquals(90, DailyHourlySummaries.forDate(withTotal, start, source, zone).noonCloudPercent)
        val bandOnly = series(at(start, 0), at(start.plusDays(1), 0), cloudLow = { 15 }, cloud = { null })
        assertEquals(15, DailyHourlySummaries.forDate(bandOnly, start, source, zone).noonCloudPercent)
        val none = series(at(start, 0), at(start.plusDays(1), 0), cloudLow = { null }, cloud = { null })
        assertNull(DailyHourlySummaries.forDate(none, start, source, zone).noonCloudPercent)
    }

    @Test
    fun `untagged download rows count as the fetched source`() {
        val rows = series(at(start, 0), at(start.plusDays(1), 0), sourceId = null)
        assertEquals(40, DailyHourlySummaries.forDate(rows, start, source, zone).noonCloudPercent)
    }

    /** Equivalence: the stored noon value is exactly what the display would have read from hourly. */
    @Test
    fun `noon equals DailyNoonCloudCover over the same rows for many random days`() {
        val random = Random(20261010)
        repeat(200) { i ->
            val day = start.plusDays(i.toLong())
            val rows = series(at(day, 0), at(day.plusDays(1), 0), cloudLow = {
                if (random.nextInt(3) == 0) null else random.nextInt(101)
            }, cloud = { if (random.nextInt(5) == 0) null else random.nextInt(101) })
            assertEquals(
                "day $day",
                DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(rows, day, source, source, zone),
                DailyHourlySummaries.forDate(rows, day, source, zone).noonCloudPercent,
            )
        }
    }

    @Test
    fun `carry forward never puts null over a value`() {
        val prior = DailyHourlySummaries.Summary(noonCloudPercent = 60, dayPrecipMax = 30, nightPrecipMax = 20)
        assertEquals(prior, DailyHourlySummaries.carryForward(DailyHourlySummaries.Summary(), prior))
        assertEquals(
            DailyHourlySummaries.Summary(10, 30, 20),
            DailyHourlySummaries.carryForward(DailyHourlySummaries.Summary(noonCloudPercent = 10), prior),
        )
    }

    @Test
    fun `carry forward takes every incoming value, and nothing from no prior`() {
        val incoming = DailyHourlySummaries.Summary(0, 0, 100)
        assertEquals(incoming, DailyHourlySummaries.carryForward(incoming, DailyHourlySummaries.Summary(60, 30, 20)))
        assertEquals(DailyHourlySummaries.Summary(), DailyHourlySummaries.carryForward(DailyHourlySummaries.Summary(), null))
    }

    @Test
    fun `download per field, stored rows for what it misses`() {
        // A Google one-page fetch: 24 h of download; hours 25–72 still stored from earlier.
        val download = series(at(start, 0), at(start.plusDays(1), 0), pop = { 5 })
        val stored = series(at(start, 0), at(start.plusDays(3), 0), pop = { 70 })
        val today = DailyHourlySummaries.forDate(download, stored, start, source, zone)
        assertEquals("the download covers today's day", 5, today.dayPrecipMax)
        assertEquals("tonight runs past the download", 70, today.nightPrecipMax)
        assertEquals(70, DailyHourlySummaries.forDate(download, stored, start.plusDays(1), source, zone).dayPrecipMax)
    }

    @Test
    fun `display reads the row's value first, hourly only for a row without one`() {
        val stored = series(at(start, 0), at(start.plusDays(3), 2), pop = { 20 })
        val day2 = start.plusDays(2)
        val shown = DailyHourlySummaries.liveDayNight(stored, day2, storedDayMax = 35, storedNightMax = 65, zoneId = zone)
        assertEquals(35, shown.dayMax)
        assertEquals(65, shown.nightMax)

        val noRow = DailyHourlySummaries.liveDayNight(stored, day2, storedDayMax = null, storedNightMax = null, zoneId = zone)
        assertEquals("pre-upgrade row: the hourly max, as before", 20, noRow.dayMax)
        assertEquals("partial night: the partial max, as before", 20, noRow.nightMax)

        val far = DailyHourlySummaries.liveDayNight(stored, start.plusDays(5), storedDayMax = 40, storedNightMax = null, zoneId = zone)
        assertEquals(40, far.dayMax)
        assertNull("no row value, no hourly: the caller falls back to the provider's", far.nightMax)
    }

    /**
     * Emulator, 2026-10-10: Open-Meteo hours past 72 h from the 07:48 fetch stayed stored (the trim
     * deletes nothing) while the 11:03 fetch wrote fresher values on the row. The row must win.
     */
    @Test
    fun `older hours past 72 h never override a fresher row`() {
        val day6 = start.plusDays(6)
        val oldHours = series(at(day6, 0), at(day6.plusDays(1), 12), pop = { 90 })
        val shown = DailyHourlySummaries.liveDayNight(oldHours, day6, storedDayMax = 10, storedNightMax = 5, zoneId = zone)
        assertEquals(10, shown.dayMax)
        assertEquals(5, shown.nightMax)
    }

    @Test
    fun `keep-until is the current hour plus the horizon`() {
        val now = at(start, 9) + 24 * 60_000L
        assertEquals(at(start, 9) + 72 * 3_600_000L, DailyHourlySummaries.keepUntilMs(now, 72))
    }
}
