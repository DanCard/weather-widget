package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneOffset

/** The one daily-row write rule both platforms store through (`plans/261024-shared-forecast-persist-rules.md`). */
@Category(ShortDuration::class)
class DailyForecastWriteTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-10-24")
    private fun at(hour: Int) = today.atTime(hour, 0).toInstant(zone).toEpochMilli()

    private fun day(date: LocalDate, high: Float?, low: Float?, summary: DailyHourlySummaries.Summary = DailyHourlySummaries.Summary()) =
        DailyForecastWrite.Incoming(date, high, low, "Clear", summary = summary)

    private fun stored(
        date: LocalDate,
        high: Float?,
        low: Float?,
        fetchedAt: Long,
        batch: Long = fetchedAt,
        predicted: LocalDate = today,
        summary: DailyHourlySummaries.Summary = DailyHourlySummaries.Summary(),
        ref: String = "$date@$fetchedAt",
    ) = DailyForecastWrite.Stored(
        date,
        DailyForecastWrite.Values(high, low, "Clear", null, null, null, null, null, null, null, summary),
        dateOfPrediction = predicted.toEpochDay() * 86_400_000L,
        lastConfirmedAt = batch,
        fetchedAt = fetchedAt,
        ref = ref,
    )

    private fun plan(
        incoming: List<DailyForecastWrite.Incoming>,
        stored: List<DailyForecastWrite.Stored<String>> = emptyList(),
        nowMs: Long = at(3),
        userRefresh: Boolean = false,
    ) = DailyForecastWrite.plan(incoming, stored, "OPEN_METEO", nowMs, nowMs, userRefresh, zone)

    @Test
    fun `past days, climate normals and an ended period are not written`() {
        val p = plan(
            listOf(
                day(today.minusDays(1), 70f, 50f),
                day(today.plusDays(1), 70f, 50f).copy(isClimateNormal = true),
                day(today, 70f, 50f).copy(periodEndMs = at(2)),
                day(today.plusDays(2), 71f, 51f),
            ),
        )
        assertEquals(listOf(today.plusDays(2)), p.write.map { it.date })
        assertTrue(p.lines.any { it.tag == "SNAPSHOT_SKIP_ELAPSED" })
        assertEquals(today.plusDays(2)..today.plusDays(2), DailyForecastWrite.readRange(listOf(day(today.minusDays(1), 1f, 1f), day(today.plusDays(2), 1f, 1f)), at(3), zone))
    }

    @Test
    fun `a day sent with neither high nor low is not written`() {
        assertTrue(plan(listOf(day(today.plusDays(1), null, null))).write.isEmpty())
    }

    @Test
    fun `after the high cutoff the prior high stays and the sent one goes to hindcast`() {
        val p = plan(listOf(day(today, 90f, 55f)), listOf(stored(today, 80f, 55f, at(5))), nowMs = at(17))
        val v = p.write.single().values
        assertEquals(80f, v.highTemp)
        assertEquals(90f, v.hindcastHighTemp)
        assertEquals(55f, v.lowTemp) // frozen low = prior's
        assertTrue(p.lines.any { it.tag == "SNAPSHOT_SKIP_HINDCAST" })
    }

    @Test
    fun `the prior is the newest by prediction date, then batch, then fetch`() {
        val p = plan(
            listOf(day(today, 90f, 50f)),
            listOf(
                stored(today, 70f, 50f, fetchedAt = at(5), predicted = today.minusDays(1)),
                stored(today, 81f, 50f, fetchedAt = at(4), batch = at(5)),
                stored(today, 82f, 50f, fetchedAt = at(5), batch = at(4)),
            ),
            nowMs = at(17),
        )
        assertEquals(81f, p.write.single().values.highTemp)
    }

    @Test
    fun `an unchanged row is re-stamped, not written - unless the user refreshed`() {
        val prior = stored(today.plusDays(1), 70f, 50f, fetchedAt = at(1), ref = "prior")
        val quiet = plan(listOf(day(today.plusDays(1), 70f, 50f)), listOf(prior))
        assertTrue(quiet.write.isEmpty())
        assertEquals(listOf("prior"), quiet.restamp)
        assertTrue(quiet.lines.any { it.tag == "SNAPSHOT_SKIP" })

        val refresh = plan(listOf(day(today.plusDays(1), 70f, 50f)), listOf(prior), userRefresh = true)
        assertEquals(1, refresh.write.size)
        assertTrue(refresh.restamp.isEmpty())
        assertTrue(refresh.lines.any { it.tag == "SNAPSHOT_SAVE" && it.message.endsWith("user_refresh=true") })

        // A user Refresh within a minute of the prior write is a duplicate: re-stamped.
        val dup = plan(listOf(day(today.plusDays(1), 70f, 50f)), listOf(prior), nowMs = at(1) + 30_000L, userRefresh = true)
        assertEquals(listOf("prior"), dup.restamp)
    }

    @Test
    fun `a row that fills a missing side is an upgrade`() {
        val p = plan(listOf(day(today.plusDays(1), 70f, 50f)), listOf(stored(today.plusDays(1), 70f, null, at(1))))
        assertEquals(1, p.write.size)
        assertTrue(p.lines.any { it.tag == "SNAPSHOT_UPGRADE" })
    }

    @Test
    fun `today with no low keeps the newest stored low that is recent`() {
        val p = plan(
            listOf(day(today, 70f, null)),
            listOf(stored(today, 69f, 48f, fetchedAt = at(1)), stored(today, 69f, null, fetchedAt = at(2))),
            nowMs = at(4),
        )
        assertEquals(48f, p.write.single().values.lowTemp)
    }

    @Test
    fun `a window this fetch did not cover keeps the prior summary`() {
        val p = plan(
            listOf(day(today.plusDays(5), 70f, 50f, DailyHourlySummaries.Summary(nightPrecipMax = 30))),
            listOf(stored(today.plusDays(5), 70f, 50f, at(1), summary = DailyHourlySummaries.Summary(noonCloudPercent = 40, nightPrecipMax = 10))),
        )
        val summary = p.write.single().values.summary
        assertEquals(40, summary.noonCloudPercent)
        assertEquals(30, summary.nightPrecipMax)
        assertNull(summary.dayPrecipMax)
    }
}
