package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.history.ProviderHistoryDecision
import com.weatherwidget.shared.history.WeatherApiHistoryRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What [WeatherApiHistoryRun.Host]'s database side means — the stored-coverage read, the store and
 * the cooldown memory — on Android's Room Host and desktop's SQLite Host
 * (plans/261024-host-contract-tests-room-and-desktop-sqlite.md). The history fetch is network.
 */
abstract class WeatherApiHistoryHostContract {
    /** Writes an observation row as given, through the platform's database. */
    fun interface Seed {
        fun observation(row: ObservationReading)
    }

    /** A fresh, empty database with a Host at [LAT], [LON] (raw device coordinates, as the apps pass). */
    protected abstract fun open(): Pair<WeatherApiHistoryRun.Host, Seed>

    private lateinit var host: WeatherApiHistoryRun.Host
    private lateinit var seed: Seed

    @Before
    fun openDatabase() {
        val (h, s) = open()
        host = h
        seed = s
    }

    @Test
    fun `storedObservations - WeatherAPI rows only, start inclusive, end exclusive`() = runBlocking {
        seed.observation(obs(DAY_START, 50f))
        seed.observation(obs(DAY_START + HOUR, 51f, api = "NWS"))
        seed.observation(obs(DAY_START + DAY, 52f))

        assertEquals(
            listOf(DAY_START to 50f),
            host.storedObservations(DAY_START, DAY_START + DAY).map { it.timestamp to it.temperature },
        )
    }

    /** What the run needs from a store: the next check reads the day as covered, and a re-store adds no rows. */
    @Test
    fun `stored hours read back as WeatherAPI observations and cover the day`() = runBlocking {
        val hours = (0 until 24).map { h -> HourlyForecast(DAY_START + h * HOUR, 40f + h, "Clear", source = "WEATHER_API") }
        host.store(hours, DATE)
        host.store(hours, DATE)

        val stored = host.storedObservations(DAY_START, DAY_START + DAY)
        assertEquals(hours.map { it.dateTime to it.temperature }, stored.sortedBy { it.timestamp }.map { it.timestamp to it.temperature })
        assertTrue(stored.all { it.api == "WEATHER_API" })
        val decision = WeatherApiHistoryRun.decide(LAT, LON, stored, latestResult = null, nowMs = DAY_START + DAY + 12 * HOUR, zoneId = ZoneOffset.UTC)
        assertTrue("$decision", decision is ProviderHistoryDecision.AlreadyCovered)
    }

    @Test
    fun `latestResult - the result line for this prefix, not another site's`() = runBlocking {
        host.log(WeatherApiHistoryRun.RESULT_TAG, "site=A date=$DATE result=cooldown retryAtMs=5", "WARN")
        host.log(WeatherApiHistoryRun.RESULT_TAG, "site=B date=$DATE result=stored hours=24", "INFO")
        host.log("OTHER_TAG", "site=A date=$DATE result=stored", "INFO")

        assertEquals("site=A date=$DATE result=cooldown retryAtMs=5", host.latestResult("site=A date=$DATE"))
        assertEquals(null, host.latestResult("site=C"))
    }

    companion object {
        const val LAT = 37.4166014
        const val LON = -122.0888722
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        val DATE: LocalDate = LocalDate.of(2026, 10, 20)
        val DAY_START: Long = DATE.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        fun obs(timestamp: Long, temp: Float, api: String = "WEATHER_API") = ObservationReading(
            stationId = "WEATHER_API_MAIN", stationName = "WeatherAPI", timestamp = timestamp, temperature = temp,
            condition = "Clear", locationLat = 37.417, locationLon = -122.089, api = api, fetchedAt = DAY_START,
        )
    }
}
