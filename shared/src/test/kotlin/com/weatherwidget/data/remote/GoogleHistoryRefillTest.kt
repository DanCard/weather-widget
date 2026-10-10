package com.weatherwidget.data.remote

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Category(ShortDuration::class)
class GoogleHistoryRefillTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private fun row(hour: Long, fetchedAt: Long, temp: Float = 70f) =
        HourlyForecast(dateTime = hour, temperature = temp, condition = "Clear", fetchedAt = fetchedAt)
    private fun hours(from: String, toExclusive: String): List<Long> =
        generateSequence(at(from)) { it + 3_600_000L }.takeWhile { it < at(toExclusive) }.toList()

    /** 2026-10-09: every hour of the day kept the 04:54 fetch; refreshed at 07:09 the next day. */
    @Test
    fun `oct 9 shape - hours more than 6 h past the 04_54 fetch are stale`() {
        val fetched = at("2026-10-09T04:54")
        val now = at("2026-10-10T07:09")
        val live = hours("2026-10-09T00:00", "2026-10-10T00:00").map { row(it, fetched) } +
            hours("2026-10-10T00:00", "2026-10-10T08:00").map { row(it, at("2026-10-10T07:08")) }

        val stale = GoogleHistoryRefill.staleHours(live, now)

        // Window starts 2026-10-09T07:09 → first whole hour 08:00; 11:00 is the first > 6 h past 04:54.
        assertEquals(hours("2026-10-09T11:00", "2026-10-10T00:00").toSet(), stale)
    }

    @Test
    fun `routine 4 to 6 h cadence leaves nothing stale`() {
        val now = at("2026-10-10T12:30")
        val fetches = listOf("2026-10-09T08:00", "2026-10-09T13:00", "2026-10-09T19:00", "2026-10-10T01:00", "2026-10-10T07:00")
            .map(::at)
        val live = hours("2026-10-09T10:00", "2026-10-10T13:00").map { hour ->
            row(hour, fetches.last { it <= hour })
        }

        assertTrue(GoogleHistoryRefill.staleHours(live, now).isEmpty())
    }

    @Test
    fun `an hour with no live row is stale`() {
        val now = at("2026-10-10T12:30")
        val live = hours("2026-10-09T13:00", "2026-10-10T12:00")
            .filter { it != at("2026-10-10T03:00") }
            .map { row(it, it - 3_600_000L) }

        assertEquals(setOf(at("2026-10-10T03:00")), GoogleHistoryRefill.staleHours(live, now))
    }

    @Test
    fun `stale hours request history only for a refreshed previous day`() {
        val stale = setOf(at("2026-10-09T15:00"))
        assertFalse(GoogleHistoryRefill.shouldRequest(needsHistory = false, staleHours = stale, refillDay = null))
        assertTrue(GoogleHistoryRefill.shouldRequest(needsHistory = false, staleHours = stale, refillDay = LocalDate.of(2026, 10, 9)))
    }

    @Test
    fun `a fresh site requests history on any fetch`() {
        assertTrue(GoogleHistoryRefill.shouldRequest(needsHistory = true, staleHours = emptySet(), refillDay = null))
    }

    @Test
    fun `only a day before today is a refill day`() {
        val today = LocalDate.of(2026, 10, 10)
        assertEquals(LocalDate.of(2026, 10, 9), GoogleHistoryRefill.refillDay(LocalDate.of(2026, 10, 9), today))
        assertEquals(null, GoogleHistoryRefill.refillDay(today, today))
        assertEquals(null, GoogleHistoryRefill.refillDay(LocalDate.of(2026, 10, 11), today))
    }

    @Test
    fun `a refill day keeps only that day's stale hours`() {
        val now = at("2026-10-10T07:09")
        val live = hours("2026-10-09T00:00", "2026-10-10T07:00").map { row(it, at("2026-10-09T00:00")) }

        val stale = GoogleHistoryRefill.staleHours(live, now, LocalDate.of(2026, 10, 9), zone)

        // Window from 07:09 yesterday: 08:00 is the first whole hour; today's stale hours are excluded.
        assertEquals(hours("2026-10-09T08:00", "2026-10-10T00:00").toSet(), stale)
    }

    @Test
    fun `a refresh right after a refill finds nothing stale`() {
        val now = at("2026-10-10T07:09")
        val live = hours("2026-10-09T08:00", "2026-10-10T07:00").map { row(it, now) }

        val stale = GoogleHistoryRefill.staleHours(live, now)

        assertTrue(stale.isEmpty())
        assertFalse(GoogleHistoryRefill.shouldRequest(needsHistory = false, staleHours = stale, refillDay = LocalDate.of(2026, 10, 9)))
    }

    @Test
    fun `select keeps only stale hours, one row each`() {
        val stale = setOf(at("2026-10-09T14:00"), at("2026-10-09T15:00"))
        val history = listOf(
            row(at("2026-10-09T13:00"), 0, 68f),
            row(at("2026-10-09T14:00"), 0, 69f),
            row(at("2026-10-09T15:00"), 0, 69.5f),
            row(at("2026-10-09T15:00"), 0, 69.4f),
        )

        val selected = GoogleHistoryRefill.select(history, stale)

        assertEquals(listOf(at("2026-10-09T14:00"), at("2026-10-09T15:00")), selected.map { it.dateTime })
        assertEquals(69.4f, selected.last().temperature)
    }
}
