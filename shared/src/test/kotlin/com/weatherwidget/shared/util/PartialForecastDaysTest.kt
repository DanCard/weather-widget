package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(ShortDuration::class)
class PartialForecastDaysTest {
    private data class Row(val high: Float?, val low: Float?, val fetchedAt: Long)
    private val today = LocalDate.parse("2026-10-02")

    @Test
    fun `complete replacement takes the newest row with both values`() {
        val pick = PartialForecastDays.completeReplacement(
            listOf(Row(91f, 62f, 1), Row(92f, null, 3), Row(90f, 61f, 2)),
            { it.high }, { it.low }, { it.fetchedAt },
        )
        assertEquals(Row(90f, 61f, 2), pick)
        assertNull(PartialForecastDays.completeReplacement(listOf(Row(92f, null, 3)), { it.high }, { it.low }, { it.fetchedAt }))
    }

    private fun todayRow(batch: Row?, stored: List<Row>) =
        PartialForecastDays.todayRow(batch, stored, { it.high }, { it.low }, { it.fetchedAt })

    @Test
    fun `today row - a complete batch row wins over any stored row`() {
        assertEquals(Row(92f, 68f, 5), todayRow(Row(92f, 68f, 5), listOf(Row(91f, 67f, 9))))
    }

    @Test
    fun `today row - a partial batch row yields to the newest complete stored row`() {
        // NWS / Open-Meteo in the evening: the batch keeps today's high but has no low.
        assertEquals(
            Row(90f, 67f, 4),
            todayRow(Row(92f, null, 9), listOf(Row(91f, 68f, 2), Row(90f, 67f, 4), Row(89f, null, 8))),
        )
    }

    @Test
    fun `today row - a complete stored row more than a day older than the newest does not replace it`() {
        // 2026-10-09 Pixel: the site's only complete Open-Meteo row for Oct 9 was fetched Oct 2
        // (90/74); that day's fetch said 69.5 with no low. The week-old row drew as today's forecast.
        val day = 86_400_000L
        val oct2 = Row(90f, 74f, 0)
        val oct9 = Row(69.5f, null, 7 * day)
        assertEquals(oct9, todayRow(oct9, listOf(oct2, oct9)))
        assertEquals(oct9, todayRow(null, listOf(oct2, oct9)))
        // Within the window the morning's complete row still fills the evening's missing low.
        val morning = Row(70f, 56f, 7 * day - 10 * 3_600_000L)
        assertEquals(morning, todayRow(oct9, listOf(oct2, morning, oct9)))
    }

    @Test
    fun `today row - a batch with no row for today still finds the stored complete row`() {
        // Silurian after 17:00 PDT: the batch starts at tomorrow. Desktop drew the climate normal here.
        assertEquals(
            Row(90.1f, 67.4f, 437),
            todayRow(null, listOf(Row(90.1f, 67.4f, 437), Row(89.6f, null, 1408), Row(91f, 68.2f, 2250 - 2400))),
        )
    }

    @Test
    fun `today row - with no complete row the newest one-sided row stands, batch row first`() {
        assertEquals(Row(92f, null, 3), todayRow(Row(92f, null, 3), listOf(Row(89f, null, 7))))
        assertEquals(Row(89f, null, 7), todayRow(null, listOf(Row(88f, null, 2), Row(89f, null, 7), Row(null, null, 9))))
    }

    @Test
    fun `today row - nothing stored leaves today empty for the climate normal`() {
        assertNull(todayRow(null, emptyList()))
        assertNull(todayRow(null, listOf(Row(null, null, 1))))
    }

    @Test
    fun `today range falls back to the hourly extremes only for the missing side`() {
        assertEquals(92f to 58f, PartialForecastDays.todayForecastRange(92f, null, listOf(60f, 58f, 90f)))
        assertEquals(92f to 61f, PartialForecastDays.todayForecastRange(92f, 61f, listOf(60f, 58f, 95f)))
        assertEquals(null to null, PartialForecastDays.todayForecastRange(null, null, emptyList()))
    }

    @Test
    fun `a low-only future day keeps its low and takes the normal's high`() {
        // Was the NWS-only terminal exemption (drawn low alone) or a whole climate day; now every
        // source's low-only day: plans/261124-partial-high-days-shared-row-choice-claude.md
        assertEquals(
            PartialForecastDays.FutureValues(75f, 58f, isClimateOverlay = false, highIsClimateNormal = true),
            PartialForecastDays.fillPartial(null, 58f, normal = 75f to 55f),
        )
    }

    @Test
    fun `a high-only future day takes both values from the normal`() {
        assertEquals(
            PartialForecastDays.FutureValues(75f, 55f, isClimateOverlay = true, highIsClimateNormal = false),
            PartialForecastDays.fillPartial(80f, null, normal = 75f to 55f),
        )
    }

    @Test
    fun `a complete day, or a partial one with no normal yet, is unchanged`() {
        assertEquals(
            PartialForecastDays.FutureValues(80f, 60f, isClimateOverlay = false, highIsClimateNormal = false),
            PartialForecastDays.fillPartial(80f, 60f, normal = 75f to 55f),
        )
        assertEquals(
            PartialForecastDays.FutureValues(null, 58f, isClimateOverlay = false, highIsClimateNormal = false),
            PartialForecastDays.fillPartial(null, 58f, normal = null),
        )
    }

    @Test
    fun `fillPartialFutureDays flags a low-only day of any source and leaves today alone`() {
        val normals = mapOf(
            java.time.MonthDay.from(today) to (70f to 50f),
            java.time.MonthDay.from(today.plusDays(3)) to (73f to 53f),
            java.time.MonthDay.from(today.plusDays(5)) to (75f to 55f),
        )
        fun day(offset: Long, high: Float?, low: Float?, source: String) = com.weatherwidget.data.model.DailyForecast(
            date = today.plusDays(offset).toString(), highTemp = high, lowTemp = low, condition = "Clouds", source = source,
        )
        val filled = PartialForecastDays.fillPartialFutureDays(
            listOf(
                day(0, null, 51f, "OPEN_WEATHER_MAP"),
                day(3, null, 54f, "NWS"),
                day(5, null, 54.4f, "OPEN_WEATHER_MAP"),
            ),
            normals,
            today,
        )
        println("filled: ${filled.map { "${it.date} ${it.highTemp}/${it.lowTemp} highNormal=${it.highIsClimateNormal}" }}")
        assertNull("today is left to the today-row rules", filled[0].highTemp)
        assertFalse(filled[0].highIsClimateNormal)
        assertEquals(listOf(73f to 54f, 75f to 54.4f), filled.drop(1).map { it.highTemp to it.lowTemp })
        assertTrue(filled.drop(1).all { it.highIsClimateNormal && !it.isClimateNormal })
    }

    @Test
    fun `a low-only future day whose hourly forecast exceeds the climate normal uses the hourly high normally`() {
        val zone = java.time.ZoneId.systemDefault()
        val day5 = today.plusDays(5)
        val normals = mapOf(java.time.MonthDay.from(day5) to (69.4f to 47.0f))
        val owmDay = com.weatherwidget.data.model.DailyForecast(
            date = day5.toString(), highTemp = null, lowTemp = 54f, condition = "Clouds", source = "OPEN_WEATHER_MAP",
        )
        fun hour(h: Int, temp: Float, source: String) = com.weatherwidget.data.model.HourlyForecast(
            dateTime = day5.atTime(h, 0).atZone(zone).toInstant().toEpochMilli(),
            temperature = temp,
            condition = "Clear",
            source = source,
        )

        // OWM 11 AM is 71.0° > 69.4° normal → promoted to normal forecast high (not green).
        val warmer = PartialForecastDays.fillPartialFutureDays(
            daily = listOf(owmDay),
            normals = normals,
            today = today,
            hourly = listOf(hour(8, 55.7f, "OPEN_WEATHER_MAP"), hour(11, 71.0f, "OPEN_WEATHER_MAP")),
            zoneId = zone,
        ).single()
        assertEquals(71.0f, warmer.highTemp!!, 0.01f)
        assertEquals(54f, warmer.lowTemp!!, 0.01f)
        assertFalse(warmer.highIsClimateNormal)
        assertFalse(warmer.isClimateNormal)

        // Cooler hourly (65.0° <= 69.4° normal), or another source's warmer hourly → stays green normal.
        val cooler = PartialForecastDays.fillPartialFutureDays(
            daily = listOf(owmDay),
            normals = normals,
            today = today,
            hourly = listOf(hour(11, 65.0f, "OPEN_WEATHER_MAP"), hour(11, 80.0f, "NWS")),
            zoneId = zone,
        ).single()
        assertEquals(69.4f, cooler.highTemp!!, 0.01f)
        assertTrue(cooler.highIsClimateNormal)

        // Without normals cached, hourlyHigh does not promote a partial day's high.
        assertEquals(
            PartialForecastDays.FutureValues(null, 54f, isClimateOverlay = false, highIsClimateNormal = false),
            PartialForecastDays.fillPartial(null, 54f, normal = null, hourlyHigh = 71.0f),
        )
    }

    @Test
    fun `a future day draws once it has a low`() {
        assertTrue(PartialForecastDays.isDrawableFuture(58f))
        assertFalse("a high alone waits for the normal to fill its low", PartialForecastDays.isDrawableFuture(null))
    }

    private data class SiteRow(val high: Float?, val low: Float?, val fetchedAt: Long, val lat: Double, val lon: Double)
    private val hourMs = 3_600_000L

    private fun nearestSiteRow(batch: SiteRow?, stored: List<SiteRow>, lat: Double = 37.415, lon: Double = -122.087) =
        PartialForecastDays.todayRowNearestSite(
            batch, stored, lat, lon, { it.lat }, { it.lon }, { it.high }, { it.low }, { it.fetchedAt },
        )

    @Test
    fun `nearest site - a post-cutoff move borrows the old site's complete row`() {
        // Fold, 2026-10-29: moved ~1 km at 18:51, after the 16:00 high cutoff, so the new site's
        // today rows carry neither value. The old site's 15:21 row is today's real forecast.
        val newSite18 = SiteRow(null, null, 18 * hourMs, 37.415, -122.087)
        val newSite21 = SiteRow(null, null, 21 * hourMs, 37.415, -122.087)
        val oldSite15 = SiteRow(67.0f, 48.2f, 15 * hourMs, 37.424, -122.088)
        val oldSite12 = SiteRow(66.7f, 48.2f, 12 * hourMs, 37.424, -122.088)
        val town = SiteRow(84f, 57f, 16 * hourMs, 37.377, -122.075) // newer, but farther away
        assertEquals(oldSite15, nearestSiteRow(newSite21, listOf(newSite18, newSite21, oldSite12, oldSite15, town)))
    }

    @Test
    fun `nearest site - the anchor site's own values always win`() {
        val batch = SiteRow(70f, null, 21 * hourMs, 37.415, -122.087)
        val sameSite = SiteRow(69f, 51f, 9 * hourMs, 37.415, -122.087)
        val oldSite = SiteRow(67f, 48f, 15 * hourMs, 37.424, -122.088)
        assertEquals(sameSite, nearestSiteRow(batch, listOf(batch, sameSite, oldSite)))
        // One-sided at the anchor: the column fills the other side from hourly, no other site.
        assertEquals(batch, nearestSiteRow(batch, listOf(batch, oldSite)))
    }

    @Test
    fun `nearest site - another site's row older than the replacement bound is not borrowed`() {
        val batch = SiteRow(null, null, 40 * hourMs, 37.415, -122.087)
        val stale = SiteRow(67f, 48f, 15 * hourMs, 37.424, -122.088)
        assertEquals(batch, nearestSiteRow(batch, listOf(batch, stale)))
    }
}
