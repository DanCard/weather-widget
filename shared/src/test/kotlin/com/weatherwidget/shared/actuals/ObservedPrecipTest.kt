package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.PrecipTrace
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Stations are combined per hour (nearest known gauge), never added
 * (plans/261021-observed-rain-combine-stations-not-sum.md). Each multi-station case fails on the
 * old summing code.
 */
@Category(ShortDuration::class)
class ObservedPrecipTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val date = LocalDate.of(2026, 10, 21)
    private val p0012Mm = 0.12f * 25.4f

    private fun at(hour: Int, minute: Int, day: LocalDate = date): Long =
        LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun row(
        station: String,
        distanceKm: Float,
        timestamp: Long,
        raw: String? = null,
        precipMm: Float? = null,
        api: WeatherSource = WeatherSource.NWS,
    ) = ObservationReading(
        stationId = station,
        stationName = station,
        timestamp = timestamp,
        temperature = 60f,
        condition = "",
        locationLat = 37.42,
        locationLon = -122.08,
        distanceKm = distanceKm,
        api = api.id,
        precipAmountMm = precipMm,
        isMetar = raw != null,
        rawMetar = raw,
    )

    /** A routine AO2 report at [minute] past each hour in [hours]; wet hours carry `P0012`. */
    private fun ao2Station(
        id: String,
        distanceKm: Float,
        minute: Int,
        hours: IntRange,
        wetHours: Set<Int> = emptySet(),
    ) = hours.map { h ->
        val wet = h in wetHours
        val p = if (wet) " P0012" else ""
        row(
            id, distanceKm, at(h, minute),
            raw = "$id 21${"%02d".format(h)}${"%02d".format(minute)}Z AUTO 00000KT 10SM OVC020 15/12 A2990 RMK AO2$p",
            precipMm = if (wet) p0012Mm else null,
        )
    }

    private fun hour(h: Int) = LocalDateTime.of(date, java.time.LocalTime.of(h, 0))

    @Test
    fun `three stations catching the same shower are one shower, not three`() {
        val rows = ao2Station("KNUQ", 3.8f, 55, 10..16, wetHours = setOf(14)) +
            ao2Station("KPAO", 6.1f, 47, 10..16, wetHours = setOf(14)) +
            ao2Station("KSJC", 15.9f, 53, 10..16, wetHours = setOf(14))
        val totals = ObservedPrecip.daily(rows, date, zone)
        assertEquals(p0012Mm, totals.total!!, 0.001f) // the old code: 9.14 mm
        assertEquals(p0012Mm, totals.day!!, 0.001f)
    }

    @Test
    fun `a dry nearest gauge outranks a wet one farther away`() {
        val rows = ao2Station("KNUQ", 3.8f, 55, 12..13) +
            ao2Station("KSJC", 15.9f, 53, 12..13, wetHours = setOf(12))
        val byHour = ObservedPrecip.byHour(rows, zone)
        assertEquals(0f, byHour[hour(12)]!!, 0f)
        assertEquals(0f, ObservedPrecip.daily(rows, date, zone).total!!, 0f)
    }

    @Test
    fun `a station without a rain gauge says nothing, so the next station decides`() {
        // KPAO is a manual station: no AO2, and no P group means "not reported", not dry.
        val kpao = listOf(10, 11, 12).map { h -> row("KPAO", 6.1f, at(h, 47), raw = "KPAO 21${h}47Z 33006KT 10SM BKN100 18/13 A2986") }
        val rows = kpao + ao2Station("KSJC", 15.9f, 53, 10..12, wetHours = setOf(12))
        assertEquals(p0012Mm, ObservedPrecip.byHour(rows, zone)[hour(12)]!!, 0.001f)
    }

    @Test
    fun `a gauge reported out of service is unknown, not dry`() {
        val rows = listOf(10, 11, 12).map { h ->
            row("KNUQ", 3.8f, at(h, 55), raw = "KNUQ 21${h}55Z AUTO 00000KT 9SM CLR 17/11 A2987 RMK AO2 PNO")
        }
        assertNull(ObservedPrecip.daily(rows, date, zone).total)
    }

    @Test
    fun `no station knew anything means null, every station dry means zero`() {
        val unknown = listOf(row("KNUQ", 3.8f, at(12, 0))) // a 5-minute ASOS row: no report, no amount
        assertNull(ObservedPrecip.daily(unknown, date, zone).total)
        val dry = ao2Station("KNUQ", 3.8f, 55, 0..23)
        val totals = ObservedPrecip.daily(dry, date, zone)
        assertEquals(0f, totals.total!!, 0f)
        assertEquals(0f, totals.night!!, 0f)
    }

    /** KMIA 2026-10-20, verbatim: the SPECI's P0002 is repeated in the next routine report. */
    @Test
    fun `a SPECI and the routine report after it are one amount, not two`() {
        val rows = ao2Station("KMIA", 5f, 53, 5..7) + listOf(
            row("KMIA", 5f, at(8, 45), raw = "KMIA 200845Z 34006KT 10SM -RA BKN027 BKN038 23/21 A2999 RMK AO2 RAB08 P0002 T02280211 \$", precipMm = 0.508f),
            row("KMIA", 5f, at(8, 53), raw = "KMIA 200853Z 34006KT 10SM -RA BKN027 23/21 A2999 RMK AO2 RAB08 SLP154 P0002 60002 T02280211 55006 \$", precipMm = 0.508f),
        )
        assertEquals(0.508f, ObservedPrecip.byHour(rows, zone)[hour(8)]!!, 0.001f) // the old code: 1.016
    }

    @Test
    fun `a SPECI after the routine minute counts toward the next period`() {
        val rows = ao2Station("KSJC", 15.9f, 53, 10..13) + listOf(
            row("KSJC", 15.9f, at(12, 58), raw = "KSJC 211958Z 00000KT 10SM -RA OVC110 18/11 A2986 RMK AO2 RAB56 P0001", precipMm = 0.254f),
        )
        val byHour = ObservedPrecip.byHour(rows, zone)
        assertEquals("12:53 closed a dry period", 0f, byHour[hour(12)]!!, 0f)
        assertEquals(0.254f, byHour[hour(13)]!!, 0.001f)
    }

    @Test
    fun `a trace at the nearest station is a trace at the site`() {
        val rows = ao2Station("KNUQ", 3.8f, 55, 10..11) +
            row("KNUQ", 3.8f, at(12, 55), raw = "KNUQ 211955Z AUTO 00000KT 9SM OVC110 18/11 A2986 RMK AO2 P0000", precipMm = PrecipTrace.MM)
        assertTrue(PrecipTrace.isTrace(ObservedPrecip.daily(rows, date, zone).total))
    }

    @Test
    fun `stations beyond 25 km are ignored`() {
        val rows = ao2Station("KOAK", 40f, 53, 12..12, wetHours = setOf(12))
        assertNull(ObservedPrecip.daily(rows, date, zone).total)
    }

    /** Provider history: each row is its own interval, so an hour still adds them up. */
    @Test
    fun `a single synthetic interval series keeps its per-hour sums`() {
        val rows = listOf(0, 5, 10).map { m ->
            row("TOMORROW_IO_5M_HISTORY", 0f, at(13, m), precipMm = 0.1f, api = WeatherSource.TOMORROW_IO)
        } + row("OPEN_METEO_MAIN", 0f, at(13, 0), precipMm = 0.1f, api = WeatherSource.OPEN_METEO)
        val tomorrow = ObservedPrecip.rowsFor(rows, WeatherSource.TOMORROW_IO)
        assertEquals(0.3f, ObservedPrecip.byHour(tomorrow, zone)[hour(13)]!!, 0.001f)
        val meteo = ObservedPrecip.rowsFor(rows, WeatherSource.OPEN_METEO)
        assertEquals(0.1f, ObservedPrecip.byHour(meteo, zone)[hour(13)]!!, 0.001f)
    }

    @Test
    fun `rowsFor keeps a source's own series only`() {
        val rows = listOf(
            row("KNUQ", 3.8f, 1L),
            row("NWS_BLEND", 0f, 1L),
            row("SILURIAN_MAIN", 0f, 1L, api = WeatherSource.SILURIAN),
            row("TOMORROW_IO_MAIN", 0f, 1L, api = WeatherSource.TOMORROW_IO),
        )
        assertEquals(listOf("KNUQ"), ObservedPrecip.rowsFor(rows, WeatherSource.NWS).map { it.stationId })
        assertTrue(ObservedPrecip.rowsFor(rows, WeatherSource.SILURIAN).isEmpty())
        assertTrue("only the five-minute product", ObservedPrecip.rowsFor(rows, WeatherSource.TOMORROW_IO).isEmpty())
    }
}
