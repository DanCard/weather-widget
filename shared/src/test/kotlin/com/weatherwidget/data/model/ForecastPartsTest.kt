package com.weatherwidget.data.model

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ForecastPartsTest {
    @Test
    fun `daily due and hourly due combine into the fetch`() {
        assertEquals(ForecastParts.BOTH, ForecastParts.of(daily = true, hourly = true))
        assertEquals(ForecastParts.DAILY, ForecastParts.of(daily = true, hourly = false))
        assertEquals(ForecastParts.HOURLY, ForecastParts.of(daily = false, hourly = true))
        assertNull(ForecastParts.of(daily = false, hourly = false))
    }

    @Test
    fun `only sources whose request can drop a part split`() {
        listOf(WeatherSource.GOOGLE_WEATHER, WeatherSource.NWS, WeatherSource.SILURIAN, WeatherSource.OPEN_METEO, WeatherSource.TOMORROW_IO)
            .forEach { assertTrue(it.id, ForecastParts.splits(it)) }
        listOf(WeatherSource.WEATHER_API, WeatherSource.OPEN_WEATHER_MAP)
            .forEach { assertFalse(it.id, ForecastParts.splits(it)) }
    }

    @Test
    fun `a non-splitting source requests everything`() {
        assertEquals(ForecastParts.BOTH, ForecastParts.requested(WeatherSource.WEATHER_API, ForecastParts.HOURLY))
        assertEquals(ForecastParts.BOTH, ForecastParts.requested(WeatherSource.OPEN_WEATHER_MAP, ForecastParts.DAILY))
        assertEquals(ForecastParts.DAILY, ForecastParts.requested(WeatherSource.OPEN_METEO, ForecastParts.DAILY))
    }

    @Test
    fun `hourly inside a FULL window takes the daily rows too`() {
        assertEquals(ForecastParts.BOTH, ForecastParts.withFullWindow(ForecastParts.HOURLY, isFull = true))
        assertEquals(ForecastParts.HOURLY, ForecastParts.withFullWindow(ForecastParts.HOURLY, isFull = false))
        assertEquals(ForecastParts.DAILY, ForecastParts.withFullWindow(ForecastParts.DAILY, isFull = true))
        assertNull(ForecastParts.withFullWindow(null, isFull = true))
    }

    @Test
    fun `a forced fetch takes both, an hourly-limited one only the parts due`() {
        assertEquals(ForecastParts.BOTH, ForecastParts.forForced(hourlyLimited = false, hourlyDue = false))
        assertEquals(ForecastParts.DAILY, ForecastParts.forForced(hourlyLimited = true, hourlyDue = false))
        assertEquals(ForecastParts.BOTH, ForecastParts.forForced(hourlyLimited = true, hourlyDue = true))
        assertEquals(ForecastParts.BOTH, ForecastParts.forForced(hourlyLimited = true, hourlyDue = null))
    }

    @Test
    fun `a scheduled fetch takes what is due, and daily carries hours when hourly is not judged`() {
        assertEquals(ForecastParts.HOURLY, ForecastParts.forRoutine(dailyDue = false, hourlyDue = true, isFull = false, blocked = none))
        assertEquals(ForecastParts.BOTH, ForecastParts.forRoutine(dailyDue = false, hourlyDue = true, isFull = true, blocked = none))
        assertEquals(ForecastParts.DAILY, ForecastParts.forRoutine(dailyDue = true, hourlyDue = false, isFull = true, blocked = none))
        assertNull(ForecastParts.forRoutine(dailyDue = false, hourlyDue = false, isFull = true, blocked = none))
        assertEquals(ForecastParts.BOTH, ForecastParts.forRoutine(dailyDue = true, hourlyDue = null, isFull = false, blocked = none))
        assertNull(ForecastParts.forRoutine(dailyDue = false, hourlyDue = null, isFull = false, blocked = none))
    }

    private val none = emptySet<ForecastProduct>()
    private val hoursBlocked = setOf(ForecastProduct.HOURLY)
    private val daysBlocked = setOf(ForecastProduct.DAILY)

    /** performance/261012-forecast-loop-quota-blocked-part-is-not-due.md */
    @Test
    fun `a quota-blocked hourly part is not due and never drags daily along in a FULL window`() {
        // 2026-10-11 21:36: hourly overdue, FULL window, forecast/hours refused → was BOTH every minute.
        assertNull(ForecastParts.forRoutine(dailyDue = false, hourlyDue = true, isFull = true, blocked = hoursBlocked))
        assertEquals(ForecastParts.DAILY, ForecastParts.forRoutine(dailyDue = true, hourlyDue = true, isFull = true, blocked = hoursBlocked))
        assertEquals(ForecastParts.DAILY, ForecastParts.forRoutine(dailyDue = true, hourlyDue = null, isFull = false, blocked = hoursBlocked))
        assertNull(ForecastParts.forRoutine(dailyDue = false, hourlyDue = null, isFull = false, blocked = hoursBlocked))
    }

    @Test
    fun `a quota-blocked daily part is not due and FULL does not upgrade hourly to both`() {
        assertEquals(ForecastParts.HOURLY, ForecastParts.forRoutine(dailyDue = true, hourlyDue = true, isFull = true, blocked = daysBlocked))
        assertEquals(ForecastParts.HOURLY, ForecastParts.forRoutine(dailyDue = false, hourlyDue = true, isFull = true, blocked = daysBlocked))
        assertNull(ForecastParts.forRoutine(dailyDue = true, hourlyDue = null, isFull = false, blocked = daysBlocked))
        assertNull(ForecastParts.forRoutine(dailyDue = true, hourlyDue = true, isFull = true, blocked = daysBlocked + hoursBlocked))
    }
}
