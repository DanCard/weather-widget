package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

@Category(ShortDuration::class)
class WidgetFormatUtilsTest {

    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    // --- US locale (inches) ---

    @Test
    fun `formatPrecipAmount US locale returns inches`() {
        Locale.setDefault(Locale.US)
        assertEquals("1in", formatPrecipAmount(25.4f))
    }

    @Test
    fun `formatPrecipAmount tiny amount uses 3 decimal places`() {
        Locale.setDefault(Locale.US)
        // 0.508mm = 0.02in
        assertEquals(".02in", formatPrecipAmount(0.508f))
    }

    @Test
    fun `formatPrecipAmount sub-inch uses 2 decimal places`() {
        Locale.setDefault(Locale.US)
        // 11.176mm = 0.44in
        assertEquals(".44in", formatPrecipAmount(11.176f))
    }

    @Test
    fun `formatPrecipAmount large amount uses 1 decimal place`() {
        Locale.setDefault(Locale.US)
        // 50.8mm = 2.0in
        assertEquals("2in", formatPrecipAmount(50.8f))
    }

    @Test
    fun `formatPrecipAmount fractional large amount`() {
        Locale.setDefault(Locale.US)
        // 38.1mm = 1.5in
        assertEquals("1.5in", formatPrecipAmount(38.1f))
    }

    // --- Metric locale (millimeters) ---

    @Test
    fun `formatPrecipAmount metric locale returns mm`() {
        Locale.setDefault(Locale.GERMANY)
        // >=10mm uses 0 decimal places, so 25.4 rounds to 25
        assertEquals("25mm", formatPrecipAmount(25.4f))
    }

    @Test
    fun `formatPrecipAmount small mm value uses 1 decimal`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("2.5mm", formatPrecipAmount(2.5f))
    }

    @Test
    fun `formatPrecipAmount large mm rounds to integer`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("25mm", formatPrecipAmount(25.0f))
    }

    // --- formatMissingHourRanges ---

    @Test
    fun `formatMissingHourRanges empty returns empty string`() {
        assertEquals("", formatMissingHourRanges(emptyList()))
    }

    @Test
    fun `formatMissingHourRanges single hour returns single label`() {
        assertEquals("9p", formatMissingHourRanges(listOf(LocalDateTime.of(2026, 5, 2, 21, 0))))
    }

    @Test
    fun `formatMissingHourRanges contiguous hours collapse into a range`() {
        val hours = listOf(7, 8, 9, 10).map { LocalDateTime.of(2026, 5, 2, it, 0) }
        assertEquals("7a–10a", formatMissingHourRanges(hours))
    }

    @Test
    fun `formatMissingHourRanges multiple disjoint ranges are joined with commas`() {
        val hours = listOf(7, 8, 11, 14, 15).map { LocalDateTime.of(2026, 5, 2, it, 0) }
        assertEquals("7a–8a, 11a, 2p–3p", formatMissingHourRanges(hours))
    }

    @Test
    fun `formatMissingHourRanges spans noon and midnight transitions`() {
        val hours = listOf(11, 12, 13).map { LocalDateTime.of(2026, 5, 2, it, 0) }
        assertEquals("11a–1p", formatMissingHourRanges(hours))
    }

    @Test
    fun `formatMissingHourRanges sorts unsorted input`() {
        val hours = listOf(15, 14, 7, 8).map { LocalDateTime.of(2026, 5, 2, it, 0) }
        assertEquals("7a–8a, 2p–3p", formatMissingHourRanges(hours))
    }

    @Test
    fun `formatMissingHourRanges treats day boundary as non-contiguous`() {
        val hours = listOf(
            LocalDateTime.of(2026, 5, 2, 23, 0),
            LocalDateTime.of(2026, 5, 3, 0, 0),
        )
        assertEquals("11p–12a", formatMissingHourRanges(hours))
    }

    // --- formatDateLabel ---

    @Test
    fun `formatDateLabel is weekday plus day-of-month`() {
        Locale.setDefault(Locale.US)
        // 2026-06-10 is a Wednesday.
        assertEquals("Wed 10", formatDateLabel(LocalDate.of(2026, 6, 10)))
    }

    // --- dateLabelMillis (per-day centered footer labels at THREE_DAY zoom) ---

    @Test
    fun `dateLabelMillis picks local noon for each fully visible day`() {
        val zone = ZoneId.of("UTC")
        // Window spans all of Jun 10 and Jun 11 exactly.
        val start = LocalDateTime.of(2026, 6, 10, 0, 0)
        val end = LocalDateTime.of(2026, 6, 11, 23, 0)
        val millis = dateLabelMillis(start, end, zone)

        val expected = setOf(
            LocalDateTime.of(2026, 6, 10, 12, 0).atZone(zone).toInstant().toEpochMilli(),
            LocalDateTime.of(2026, 6, 11, 12, 0).atZone(zone).toInstant().toEpochMilli(),
        )
        assertEquals(expected, millis)
    }

    @Test
    fun `dateLabelMillis clamps partial first and last days to the window edges`() {
        val zone = ZoneId.of("UTC")
        // Centered ~now: starts Jun 10 15:00 (noon already past) and ends Jun 12 15:00.
        val start = LocalDateTime.of(2026, 6, 10, 15, 0)
        val end = LocalDateTime.of(2026, 6, 12, 15, 0)
        val millis = dateLabelMillis(start, end, zone)

        // Jun 10's noon is before the window -> clamps to the start edge; Jun 11 lands on noon;
        // Jun 12's noon is in-window (15:00 end is after 12:00) -> noon. One label per visible day.
        val expected = setOf(
            start.atZone(zone).toInstant().toEpochMilli(),
            LocalDateTime.of(2026, 6, 11, 12, 0).atZone(zone).toInstant().toEpochMilli(),
            LocalDateTime.of(2026, 6, 12, 12, 0).atZone(zone).toInstant().toEpochMilli(),
        )
        assertEquals(expected, millis)
    }
}
