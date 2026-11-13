package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.HourlyActual
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.stats.RainAccuracyReport
import com.weatherwidget.shared.stats.RainAccuracyRunner
import com.weatherwidget.shared.stats.RainAccuracySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * One rain-accuracy scenario (plans/261113-per-source-rain-score.md), seeded into Android's Room
 * Host, desktop's SQLite Host and an in-memory [FakeHost]: every Host must yield the report the fake
 * does, so the two platforms grade rain identically.
 *
 * Sources A = Google, B = NWS. Measured rain is one KNUQ gauge (NWS feed), the same for both.
 * - **Nov 13:** 6 wet day hours (2 mm each) + 2 wet night hours (3 mm each) = 18 mm. A: day 6 / night 12
 *   = 18 (total exact); B: day 12 / night 0 = 12. B is exact on the 8a–8p bucket, A on the day: ★ A.
 * - **Nov 12:** measured 1 mm at 9:00 and 5 mm at 14:00. A forecast 0 at 9:00 and has *no* amount for
 *   14:00; B forecast 1 and 3. On their own hours A misses by 1, B by 2 — but A only "wins" by
 *   skipping the wettest hour. On the hours both share, B is exact: ★ B.
 * - **Nov 11:** live gauge 00–09 dry, NWS archive for all 24 h with 9 mm at 05:00. The live 0 wins,
 *   the archive fills 10–23: a counted dry day.
 * - **Nov 10:** only 5 gauge hours: not scored.
 * Noise that must not count: an Open-Meteo `_MAIN` "actual" and the NWS blend reporting 50 mm, an
 * older snapshot (99 mm) and a same-day snapshot (77 mm).
 */
abstract class RainAccuracyHostContract {
    /** A fresh, empty database each call. */
    protected abstract fun open(): RainAccuracyRunner.Host

    protected abstract suspend fun seedSnapshots(source: WeatherSource, bucketMs: Long, rows: List<RainAccuracyScenario.Hour>)

    protected abstract suspend fun seedObservations(rows: List<ObservationReading>)

    protected abstract suspend fun seedArchive(rows: List<HourlyActual>)

    private suspend fun seedScenario(): RainAccuracyRunner.Host {
        val host = open()
        RainAccuracyScenario.snapshots.forEach { (key, rows) -> seedSnapshots(key.first, key.second, rows) }
        seedObservations(RainAccuracyScenario.observations)
        seedArchive(RainAccuracyScenario.archive)
        return host
    }

    @Test
    fun `the host's report equals the scenario's`() = runBlocking {
        val actual = RainAccuracyScenario.run(seedScenario())
        val expected = RainAccuracyScenario.run(RainAccuracyScenario.FakeHost())
        println("RAIN_CONTRACT scores=${actual.scores}")
        assertEquals(expected, actual)
        RainAccuracyScenario.assertExpected(actual)
    }
}

object RainAccuracyScenario {
    val zone: ZoneId = ZoneId.of("America/Los_Angeles")
    val today: LocalDate = LocalDate.of(2026, 11, 14)
    const val DAYS = 4
    const val LAT = 37.42
    const val LON = -122.08
    val A = WeatherSource.GOOGLE_WEATHER
    val B = WeatherSource.NWS
    val sources = listOf(A, B)

    val d10: LocalDate = LocalDate.of(2026, 11, 10)
    val d11: LocalDate = LocalDate.of(2026, 11, 11)
    val d12: LocalDate = LocalDate.of(2026, 11, 12)
    val d13: LocalDate = LocalDate.of(2026, 11, 13)

    data class Hour(val dateTimeMs: Long, val precipAmountMm: Float?)

    fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun hours(date: LocalDate, amount: (Int) -> Float?): List<Hour> = (0..23).map { Hour(at(date, it), amount(it)) }

    /** Predicted amount per hour, by (source, date). Null = the snapshot carries no amount. */
    private val predicted: Map<Pair<WeatherSource, LocalDate>, (Int) -> Float?> = mapOf(
        (A to d10) to { _ -> 0f },
        (B to d10) to { _ -> 0f },
        (A to d11) to { _ -> 0f },
        (B to d11) to { _ -> 0f },
        (A to d12) to { h -> if (h == 14) null else 0f },
        (B to d12) to { h -> when (h) { 9 -> 1f; 14 -> 3f; else -> 0f } },
        (A to d13) to { h -> when (h) { in 10..15 -> 1f; 21, 22 -> 6f; else -> 0f } },
        (B to d13) to { h -> if (h in 10..15) 2f else 0f },
    )

    /** (source, bucket) → hours. Each date: the prior-day noon snapshot, plus noise either side. */
    val snapshots: Map<Pair<WeatherSource, Long>, List<Hour>> = buildMap {
        for ((key, amount) in predicted) {
            val (source, date) = key
            put(source to at(date.minusDays(1), 12), hours(date, amount))
            put(source to at(date.minusDays(1), 3), hours(date) { 99f }) // older: not the newest
            put(source to at(date, 6), hours(date) { 77f }) // captured on the day: outside the window
        }
    }

    private fun gauge(date: LocalDate, hour: Int, mm: Float, station: String = "KNUQ", api: String = "NWS") =
        ObservationReading(
            stationId = station,
            stationName = station,
            timestamp = at(date, hour, 53),
            temperature = 50f,
            condition = "Rain",
            locationLat = LAT,
            locationLon = LON,
            distanceKm = 3f,
            stationType = StationType.OFFICIAL,
            api = api,
            fetchedAt = at(date, hour, 55),
            precipAmountMm = mm,
        )

    private val measured: Map<LocalDate, Map<Int, Float>> = mapOf(
        d10 to (0..4).associateWith { 0f },
        d11 to (0..9).associateWith { 0f },
        d12 to (0..23).associateWith { h -> when (h) { 9 -> 1f; 14 -> 5f; else -> 0f } },
        d13 to (0..23).associateWith { h -> when (h) { in 10..15 -> 2f; 21, 22 -> 3f; else -> 0f } },
    )

    val observations: List<ObservationReading> =
        measured.flatMap { (date, byHour) -> byHour.map { (h, mm) -> gauge(date, h, mm) } } +
            listOf(
                gauge(d13, 12, 50f, station = "OPEN_METEO_MAIN", api = "OPEN_METEO"),
                gauge(d13, 12, 50f, station = "NWS_BLEND"),
            )

    val archive: List<HourlyActual> = (0..23).map { h ->
        HourlyActual(
            hourMs = at(d11, h),
            source = WeatherSource.NWS.id,
            locationLat = LAT,
            locationLon = LON,
            precipAmountMm = if (h == 5) 9f else 0f,
            archivedAt = at(d11, 23),
        )
    }

    suspend fun run(host: RainAccuracyRunner.Host): RainAccuracyReport =
        RainAccuracyRunner(host).run(sources, LAT, LON, DAYS, zone, today)

    fun assertExpected(report: RainAccuracyReport) {
        val c13 = report.comparisons.getValue(d13)
        assertEquals("Nov 13 ★ goes to the exact daily total", setOf(A), c13.closest)
        assertEquals(18f, c13.actualMm!!, 1e-4f)
        val b13 = report.days.single { it.date == d13.toString() && it.source == B }
        assertEquals("B was exact on 8a–8p alone", b13.actualDayMm!!, b13.predDayMm!!, 1e-4f)

        val c12 = report.comparisons.getValue(d12)
        assertEquals("Nov 12: A cannot win by skipping the wet hour", setOf(B), c12.closest)
        assertEquals(23, c12.commonHours)

        val a11 = report.days.single { it.date == d11.toString() && it.source == A }
        assertEquals("live gauge beats the archive's 9 mm", 0f, a11.actualTotalMm!!, 0f)
        assertEquals(24, a11.pairedHours)

        val c10 = report.comparisons.getValue(d10)
        assertEquals(null, c10.actualMm)
        assertEquals(5, c10.commonHours)

        val a = report.scores.single { it.source == A }
        val b = report.scores.single { it.source == B }
        assertEquals(3, a.countedDays)
        assertEquals(2, a.rainDays)
        assertEquals(0.5f, a.meanAbsErrorMm!!, 1e-4f)
        assertEquals(2f / 3f, a.hitRate!!, 1e-4f)
        assertEquals(4f, b.meanAbsErrorMm!!, 1e-4f)
        assertEquals(-4f, b.biasMm!!, 1e-4f)
        assertEquals(1f, b.hitRate!!, 1e-4f)
        assertTrue("two rain days: listed, not ranked", report.scores.none { it.ranked })
        assertTrue(
            RainAccuracySummary.scoreLines(report).all { it is RainAccuracySummary.ScoreLine.TooFewRainDays },
        )
    }

    /** The scenario held in memory, filtered exactly as the Room/SQLite queries filter. */
    class FakeHost : RainAccuracyRunner.Host {
        override suspend fun forecastSnapshots(
            source: WeatherSource,
            startMs: Long,
            endMs: Long,
            bucketStartMs: Long,
            bucketEndMs: Long,
            lat: Double,
            lon: Double,
        ): List<RainAccuracyRunner.PrecipSnapshot> =
            snapshots.filterKeys { (s, bucket) -> s == source && bucket >= bucketStartMs && bucket < bucketEndMs }
                .flatMap { (key, rows) ->
                    rows.filter { it.dateTimeMs >= startMs && it.dateTimeMs < endMs }
                        .map { RainAccuracyRunner.PrecipSnapshot(it.dateTimeMs, key.second, it.precipAmountMm) }
                }
                .sortedWith(compareBy<RainAccuracyRunner.PrecipSnapshot> { it.dateTime }.thenByDescending { it.timestampToGroupPredictions })

        override suspend fun observations(startMs: Long, endMs: Long, lat: Double, lon: Double): List<ObservationReading> =
            RainAccuracyScenario.observations.filter { it.timestamp >= startMs && it.timestamp < endMs }

        override suspend fun archivedActuals(startMs: Long, endMs: Long, lat: Double, lon: Double, sourceId: String): List<HourlyActual> =
            archive.filter { it.source == sourceId && it.hourMs >= startMs && it.hourMs < endMs }
    }
}
