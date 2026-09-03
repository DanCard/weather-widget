package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

@Category(ShortDuration::class)
class HourLabelFormatterTest {

    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    // --- hourLabel ---

    @Test
    fun `hourLabel midnight is 12a`() {
        assertEquals("12a", HourLabelFormatter.hourLabel(LocalDateTime.of(2026, 5, 2, 0, 0)))
    }

    @Test
    fun `hourLabel noon is 12p`() {
        assertEquals("12p", HourLabelFormatter.hourLabel(LocalDateTime.of(2026, 5, 2, 12, 0)))
    }

    @Test
    fun `hourLabel afternoon uses 12-hour clock`() {
        assertEquals("3p", HourLabelFormatter.hourLabel(LocalDateTime.of(2026, 5, 2, 15, 0)))
    }

    // --- missingHourRanges ---

    @Test
    fun `missingHourRanges empty returns empty string`() {
        assertEquals("", HourLabelFormatter.missingHourRanges(emptyList()))
    }

    @Test
    fun `missingHourRanges single hour returns single label`() {
        assertEquals("9p", HourLabelFormatter.missingHourRanges(listOf(LocalDateTime.of(2026, 5, 2, 21, 0))))
    }

    @Test
    fun `missingHourRanges contiguous hours collapse into a range`() {
        val hours = listOf(7, 8, 9, 10).map { LocalDateTime.of(2026, 5, 2, it, 0) }
        assertEquals("7a–10a", HourLabelFormatter.missingHourRanges(hours))
    }

    @Test
    fun `missingHourRanges multiple disjoint ranges are joined with commas`() {
        val hours = listOf(7, 8, 11, 14, 15).map { LocalDateTime.of(2026, 5, 2, it, 0) }
        assertEquals("7a–8a, 11a, 2p–3p", HourLabelFormatter.missingHourRanges(hours))
    }

    @Test
    fun `missingHourRanges spans noon and midnight transitions`() {
        val hours = listOf(11, 12, 13).map { LocalDateTime.of(2026, 5, 2, it, 0) }
        assertEquals("11a–1p", HourLabelFormatter.missingHourRanges(hours))
    }

    @Test
    fun `missingHourRanges sorts unsorted input`() {
        val hours = listOf(15, 14, 7, 8).map { LocalDateTime.of(2026, 5, 2, it, 0) }
        assertEquals("7a–8a, 2p–3p", HourLabelFormatter.missingHourRanges(hours))
    }

    @Test
    fun `missingHourRanges treats day boundary as non-contiguous`() {
        val hours = listOf(
            LocalDateTime.of(2026, 5, 2, 23, 0),
            LocalDateTime.of(2026, 5, 3, 0, 0),
        )
        assertEquals("11p–12a", HourLabelFormatter.missingHourRanges(hours))
    }

    // --- dateLabel ---

    @Test
    fun `dateLabel is weekday plus day-of-month`() {
        Locale.setDefault(Locale.US)
        // 2026-06-10 is a Wednesday.
        assertEquals("Wed 10", HourLabelFormatter.dateLabel(LocalDate.of(2026, 6, 10)))
    }
}
