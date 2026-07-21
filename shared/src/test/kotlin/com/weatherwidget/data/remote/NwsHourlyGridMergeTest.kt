package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class NwsHourlyGridMergeTest {

    private val keyFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")

    private fun epochMs(local: LocalDateTime) =
        local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun period(local: LocalDateTime, cloud: Int? = null, precip: Float? = null) =
        NwsApi.HourlyForecastPeriod(
            startTime = epochMs(local),
            localDate = local.toLocalDate().toString(),
            localHour = local.hour,
            temperature = 60f,
            shortForecast = "Sunny",
            cloudCover = cloud,
            precipAmountMm = precip,
        )

    // The merge keys on the system-default zone, so derive the expected key the same way.
    private fun key(local: LocalDateTime) = local.format(keyFormat)

    @Test
    fun `sky cover is merged onto matching hours`() {
        val h10 = LocalDateTime.of(2026, 6, 8, 10, 0)
        val h11 = LocalDateTime.of(2026, 6, 8, 11, 0)
        val periods = listOf(period(h10), period(h11))
        val skyCover = mapOf(key(h10) to 25, key(h11) to 80)

        val result = NwsHourlyGridMerge.applyGridpointData(periods, skyCover, emptyList())

        assertEquals(25, result[0].cloudCover)
        assertEquals(80, result[1].cloudCover)
    }

    @Test
    fun `hours without a sky cover entry stay null`() {
        val h10 = LocalDateTime.of(2026, 6, 8, 10, 0)
        val h11 = LocalDateTime.of(2026, 6, 8, 11, 0)
        val skyCover = mapOf(key(h10) to 25) // only the first hour has data

        val result = NwsHourlyGridMerge.applyGridpointData(
            listOf(period(h10), period(h11)), skyCover, emptyList(),
        )

        assertEquals(25, result[0].cloudCover)
        assertNull(result[1].cloudCover)
    }

    @Test
    fun `empty sky cover map passes periods through unchanged`() {
        val periods = listOf(period(LocalDateTime.of(2026, 6, 8, 10, 0), cloud = 7))
        val result = NwsHourlyGridMerge.applyGridpointData(periods, emptyMap(), emptyList())
        assertEquals(7, result[0].cloudCover) // pre-existing value untouched, not zeroed
    }

    @Test
    fun `grid qpf is time-weighted across the overlapping hour`() {
        val h10 = LocalDateTime.of(2026, 6, 8, 10, 0)
        val start = epochMs(h10)
        // 4mm over a 2-hour interval (10:00-12:00); the 10:00 hour overlaps half of it -> 2mm.
        val interval = NwsApi.QuantitativePrecipitationInterval(
            startTime = start,
            endTime = start + 2 * 3_600_000L,
            amountMm = 4f,
        )

        val result = NwsHourlyGridMerge.applyGridpointData(listOf(period(h10)), emptyMap(), listOf(interval))

        assertEquals(2f, result[0].precipAmountMm!!, 0.001f)
    }

    @Test
    fun `non-overlapping qpf leaves precip untouched`() {
        val h10 = LocalDateTime.of(2026, 6, 8, 10, 0)
        val start = epochMs(h10)
        val interval = NwsApi.QuantitativePrecipitationInterval(
            startTime = start + 5 * 3_600_000L,
            endTime = start + 6 * 3_600_000L,
            amountMm = 4f,
        )

        val result = NwsHourlyGridMerge.applyGridpointData(
            listOf(period(h10, precip = 0.5f)), emptyMap(), listOf(interval),
        )

        assertEquals(0.5f, result[0].precipAmountMm!!, 0.001f)
    }
}
