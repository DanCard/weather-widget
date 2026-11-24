package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.ClimateNormals
import com.weatherwidget.shared.util.ForecastPersistRun
import com.weatherwidget.shared.util.TempUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId

/**
 * A future day whose forecast has a real low and no real high, end to end on both platforms
 * (plans/261124-partial-high-days-shared-row-choice-claude.md): an OpenWeatherMap fetch made in the
 * morning reaches only the pre-dawn hours of its last day (desktop drew 57.5° for Sunday 2026-11-29
 * against 72–75° from every other source). The real [com.weatherwidget.data.remote.OpenWeatherMapApi]
 * parses an [OwmForecastFixture] (MockEngine, no network), the real [ForecastPersistRun] writes it
 * through each platform's Host into a throwaway database, and each platform's real daily read builds
 * the column.
 *
 * Halves: `DesktopPartialHighDayContractTest` (SQLite temp file) and `RoomPartialHighDayContractTest`
 * (in-memory Room, Robolectric). Neither opens the user's database.
 *
 * Dates are relative to the real today in the system zone, because both platforms' reads take today
 * from the clock; the fixture's city offset is that zone's, so the parser and the reads agree on dates.
 */
abstract class PartialHighDayContract {

    /** A stored `forecasts` row, newest first. */
    data class Raw(val high: Float?, val low: Float?, val precipProbability: Int?)

    /** The future column the daily view shows, as each platform builds it. */
    data class Shown(
        val high: Float?,
        val low: Float?,
        /** The text-mode labels as the platform prints them. */
        val highLabel: String?,
        val lowLabel: String?,
        val highIsClimateNormal: Boolean,
        /** A whole climate-normal day (both values from the normal). */
        val climateDay: Boolean,
    )

    interface Platform {
        fun persistHost(sourceId: String, lat: Double, lon: Double): ForecastPersistRun.Host<*>
        suspend fun storeNormals(lat: Double, lon: Double, monthlyHigh: Map<Int, Float>, monthlyLow: Map<Int, Float>)
        suspend fun rawDaily(lat: Double, lon: Double, sourceId: String, date: LocalDate): List<Raw>

        /** The column for [date] by the platform's daily read at [now] (its clock reads [now] too). */
        suspend fun shownDay(lat: Double, lon: Double, sourceId: String, date: LocalDate, now: LocalDateTime): Shown?
    }

    /** A fresh, empty database. */
    protected abstract fun open(): Platform

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val lastDay = today.plusDays(5)
    private val owm = WeatherSource.OPEN_WEATHER_MAP.id

    /** Monthly normals with tenths, so a climate high prints one. */
    private val monthlyHigh = (1..12).associateWith { 60.3f + it }
    private val monthlyLow = (1..12).associateWith { 40.6f + it }
    private val normals = ClimateNormals.expandMonthlyToDaily(monthlyHigh, monthlyLow)

    private fun fixture(at: LocalDateTime, slots: Int = 40) = OwmForecastFixture(
        fetchedAt = at.atZone(zone).toInstant(),
        offsetSeconds = zone.rules.getOffset(at).totalSeconds,
        slots = slots,
        pop = { t -> if (t.toLocalDate() == lastDay) 0.05 else 0.0 },
    )

    private suspend fun fetchAndPersist(platform: Platform, at: LocalDateTime, slots: Int = 40): OwmForecastFixture {
        val fixture = fixture(at, slots)
        val raw = fixture.api().getForecast(LAT, LON)
        println("fetch at $at: daily=${raw.daily.map { "${it.date} ${it.highTemp}/${it.lowTemp} pop=${it.precipProbability}" }}")
        @Suppress("UNCHECKED_CAST")
        val host = platform.persistHost(owm, LAT, LON) as ForecastPersistRun.Host<Any?>
        ForecastPersistRun(host).persist(
            ForecastPersistRun.Fetch(hourly = raw.hourly, daily = raw.daily, keep = null),
            nowMs = at.atZone(zone).toInstant().toEpochMilli(),
            zone = zone,
        )
        return fixture
    }

    private fun lastDayLocalSlots(fixture: OwmForecastFixture) =
        fixture.slotTimes.map(fixture::local).filter { it.toLocalDate() == lastDay }

    @Test
    fun `a morning fetch's cut-off last day shows its real low under the climate normal's high`() = runBlocking {
        val platform = open()
        platform.storeNormals(LAT, LON, monthlyHigh, monthlyLow)
        val morning = today.atTime(8, 35)
        val fixture = fetchAndPersist(platform, morning)
        val slots = lastDayLocalSlots(fixture)
        println("last day $lastDay slots: ${slots.map { it.hour }}")
        assertTrue("an 08:35 fetch stops in the morning of today+5: ${slots.map { it.hour }}", slots.maxOf { it.hour } in 5..14)

        val raw = platform.rawDaily(LAT, LON, owm, lastDay)
        println("raw $lastDay: $raw")
        val stored = raw.first()
        assertNull("no afternoon slot: no high stored (was the pre-dawn max)", stored.high)
        val realLow = slots.minOf { OwmForecastFixture.diurnal(it) }.toFloat()
        assertEquals(realLow, stored.low!!, 0.01f)
        assertEquals("rain chance kept as parsed", 5, stored.precipProbability)

        val shown = platform.shownDay(LAT, LON, owm, lastDay, morning.plusMinutes(1))
        println("shown $lastDay: $shown")
        assertNotNull("today+5 is drawn", shown)
        val normalHigh = normals.getValue(MonthDay.from(lastDay)).first
        assertEquals(normalHigh, shown!!.high!!, 0.01f)
        assertEquals(realLow, shown.low!!, 0.01f)
        assertTrue("green top half", shown.highIsClimateNormal)
        assertFalse("a real day, not a climate day", shown.climateDay)
        assertEquals("the climate high keeps its tenth", TempUtils.formatDayTemp(normalHigh, false, wholeDegrees = false), shown.highLabel)
        assertEquals("the real low prints whole degrees", TempUtils.formatDayTemp(realLow, false, wholeDegrees = true), shown.lowLabel)
    }

    @Test
    fun `a cut-off last day whose available hourly forecast exceeds the climate normal shows the forecast high normally`() = runBlocking {
        val platform = open()
        // Climate normal high (50.3°–62.3°) sits below the morning hourly max on lastDay.
        val coolMonthlyHigh = (1..12).associateWith { 45.3f + it * 0.1f }
        val coolMonthlyLow = (1..12).associateWith { 35.6f + it * 0.1f }
        platform.storeNormals(LAT, LON, coolMonthlyHigh, coolMonthlyLow)
        val morning = today.atTime(8, 35)
        val fixture = fetchAndPersist(platform, morning)
        val slots = lastDayLocalSlots(fixture)
        assertTrue("an 08:35 fetch stops in the morning of today+5: ${slots.map { it.hour }}", slots.maxOf { it.hour } in 5..14)

        val stored = platform.rawDaily(LAT, LON, owm, lastDay).first()
        assertNull("DB still stores null high for a LOW_ONLY day", stored.high)

        val hourlyMax = slots.maxOf { OwmForecastFixture.diurnal(it) }.toFloat()
        val normalHigh = ClimateNormals.expandMonthlyToDaily(coolMonthlyHigh, coolMonthlyLow)
            .getValue(MonthDay.from(lastDay)).first
        assertTrue("precondition: hourly max ($hourlyMax) > climate normal ($normalHigh)", hourlyMax > normalHigh)

        val shown = platform.shownDay(LAT, LON, owm, lastDay, morning.plusMinutes(1))
        println("shown $lastDay (hourlyMax=$hourlyMax > normal=$normalHigh): $shown")
        assertNotNull("today+5 is drawn", shown)
        assertEquals(hourlyMax, shown!!.high!!, 0.01f)
        assertFalse("uses forecast high normally (not green top half)", shown.highIsClimateNormal)
        assertFalse("not a whole climate day", shown.climateDay)
        assertEquals("normal forecast high prints whole degrees", TempUtils.formatDayTemp(hourlyMax, false, wholeDegrees = true), shown.highLabel)
    }

    @Test
    fun `an evening fetch that reaches the afternoon replaces the cut-off row with a real high`() = runBlocking {
        val platform = open()
        platform.storeNormals(LAT, LON, monthlyHigh, monthlyLow)
        fetchAndPersist(platform, today.atTime(8, 35))
        val evening = today.atTime(18, 5)
        val fixture = fetchAndPersist(platform, evening)
        val slots = lastDayLocalSlots(fixture)
        assertTrue("an 18:05 fetch reaches the afternoon of today+5: ${slots.map { it.hour }}", slots.maxOf { it.hour } >= 15)

        println("raw $lastDay: ${platform.rawDaily(LAT, LON, owm, lastDay)}")
        val shown = platform.shownDay(LAT, LON, owm, lastDay, evening.plusMinutes(1))
        println("shown $lastDay: $shown")
        assertEquals("the newest row's real high; no cleanup needed", slots.maxOf { OwmForecastFixture.diurnal(it) }.toFloat(), shown!!.high!!, 0.01f)
        assertFalse(shown.highIsClimateNormal)
        assertFalse(shown.climateDay)
    }

    @Test
    fun `a last day with no morning slot is not stored and shows the whole climate normal`() = runBlocking {
        val platform = open()
        platform.storeNormals(LAT, LON, monthlyHigh, monthlyLow)
        val morning = today.atTime(8, 35)
        // 38 slots end before 05:00 on today+5 (or before it).
        val fixture = fetchAndPersist(platform, morning, slots = 38)
        println("last day $lastDay slots: ${lastDayLocalSlots(fixture).map { it.hour }}")
        assertTrue(lastDayLocalSlots(fixture).all { it.hour < 5 })

        assertEquals("no OWM row for today+5", emptyList<Raw>(), platform.rawDaily(LAT, LON, owm, lastDay))
        val shown = platform.shownDay(LAT, LON, owm, lastDay, morning.plusMinutes(1))
        println("shown $lastDay: $shown")
        val normal = normals.getValue(MonthDay.from(lastDay))
        assertTrue("whole climate day", shown!!.climateDay)
        assertFalse(shown.highIsClimateNormal)
        assertEquals(normal.first, shown.high!!, 0.01f)
        assertEquals(normal.second, shown.low!!, 0.01f)
    }

    @Test
    fun `before the normals are cached a cut-off day draws its low alone`() = runBlocking {
        val platform = open()
        val morning = today.atTime(8, 35)
        val fixture = fetchAndPersist(platform, morning)
        val shown = platform.shownDay(LAT, LON, owm, lastDay, morning.plusMinutes(1))
        println("shown $lastDay (no normals): $shown")
        assertNotNull("a low-only day is drawable", shown)
        assertNull(shown!!.high)
        assertEquals(lastDayLocalSlots(fixture).minOf { OwmForecastFixture.diurnal(it) }.toFloat(), shown.low!!, 0.01f)
        assertFalse(shown.highIsClimateNormal)
        assertFalse(shown.climateDay)
    }

    companion object {
        const val LAT = 33.645
        const val LON = -111.911
    }
}
