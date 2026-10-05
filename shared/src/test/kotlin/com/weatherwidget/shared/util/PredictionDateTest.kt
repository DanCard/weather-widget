package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The prediction day is the local date. Desktop once used the UTC date and filed every fetch
 * made after 17:00 PDT under tomorrow
 * (plans/261005-desktop-forecast-today-is-utc-date-after-5pm.md).
 */
@Category(ShortDuration::class)
class PredictionDateTest {
    private val pacific = ZoneId.of("America/Los_Angeles")
    private val warsaw = ZoneId.of("Europe/Warsaw")

    private fun at(local: String, zone: ZoneId) =
        ZonedDateTime.of(java.time.LocalDateTime.parse(local), zone).toInstant().toEpochMilli()

    @Test
    fun `an evening fetch west of UTC belongs to the local day, not the UTC one`() {
        val eveningMs = at("2026-10-04T20:00", pacific) // 2026-10-05T03:00Z
        assertEquals(LocalDate.parse("2026-10-04"), PredictionDate.of(eveningMs, pacific))
    }

    @Test
    fun `the day turns at local midnight, not at 17 00 PDT`() {
        assertEquals(LocalDate.parse("2026-10-04"), PredictionDate.of(at("2026-10-04T16:59", pacific), pacific))
        assertEquals(LocalDate.parse("2026-10-04"), PredictionDate.of(at("2026-10-04T17:00", pacific), pacific))
        assertEquals(LocalDate.parse("2026-10-04"), PredictionDate.of(at("2026-10-04T23:59", pacific), pacific))
        assertEquals(LocalDate.parse("2026-10-05"), PredictionDate.of(at("2026-10-05T00:00", pacific), pacific))
    }

    @Test
    fun `an after-midnight fetch east of UTC belongs to the new local day`() {
        val ms = at("2026-09-28T00:30", warsaw) // 2026-09-27T22:30Z
        assertEquals(LocalDate.parse("2026-09-28"), PredictionDate.of(ms, warsaw))
    }

    @Test
    fun `epochMs is UTC midnight of the local date, the forecasts table encoding`() {
        val eveningMs = at("2026-10-04T20:00", pacific)
        assertEquals(LocalDate.parse("2026-10-04").toEpochDay() * 86_400_000L, PredictionDate.epochMs(eveningMs, pacific))
    }
}
