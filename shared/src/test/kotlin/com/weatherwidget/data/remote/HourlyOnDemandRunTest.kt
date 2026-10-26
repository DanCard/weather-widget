package com.weatherwidget.data.remote

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/** [HourlyOnDemandRun]: the one on-demand hourly fetch, against a fake host. */
@Category(ShortDuration::class)
class HourlyOnDemandRunTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val hour = 3_600_000L
    private val now = java.time.Instant.parse("2026-10-23T18:00:00Z").toEpochMilli()
    private val google = WeatherSource.GOOGLE_WEATHER.id
    private val target = LocalDate.of(2026, 10, 28)

    private fun hours(count: Int, fetchedAt: Long = now) =
        (0 until count).map { HourlyForecast(now + it * hour, 60f, "Clear", source = google, fetchedAt = fetchedAt) }

    private class Host(val stored: List<HourlyForecast>, val answer: () -> Boolean = { true }) : HourlyOnDemandRun.Host {
        override val lat = 37.417
        override val lon = -122.089
        val requests = mutableListOf<HourlyOnDemand.Request>()
        override suspend fun storedHourly() = stored
        override suspend fun fetch(request: HourlyOnDemand.Request): Boolean { requests += request; return answer() }
    }

    @Test
    fun aDayStoredSinceTheTapIsNotFetchedAgain() = runBlocking {
        // A quiet gap-fill or an earlier tap stored the day between the decision and this run.
        val host = Host(hours(200))
        val result = HourlyOnDemandRun.run(host, google, target, zone, now)
        assertEquals(HourlyOnDemandRun.Outcome.COVERED, result.outcome)
        assertTrue(host.requests.isEmpty())
        assertTrue(HourlyOnDemandRun.line(google, target, result).contains("outcome=covered_no_fetch"))
    }

    @Test
    fun anUncoveredDayIsFetchedDeepEnough() = runBlocking {
        val host = Host(hours(72))
        val result = HourlyOnDemandRun.run(host, google, target, zone, now)
        assertEquals(HourlyOnDemandRun.Outcome.STORED, result.outcome)
        val asked = host.requests.single()
        val dayEnd = target.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertTrue(now + (asked.hours - 1) * hour >= dayEnd - hour)
        assertEquals(asked.hours, result.hours)
    }

    @Test
    fun aFailedFetchIsReportedNotThrown() = runBlocking {
        val result = HourlyOnDemandRun.run(Host(hours(72)) { throw java.io.IOException("down") }, google, target, zone, now)
        assertEquals(HourlyOnDemandRun.Outcome.FAILED, result.outcome)
        assertEquals("WARN", HourlyOnDemandRun.level(result))
        assertTrue(HourlyOnDemandRun.line(google, target, result, "widget=7").let { it.contains("error=IOException:down") && it.endsWith("widget=7") })
    }

    @Test(expected = CancellationException::class)
    fun cancellationPropagates() {
        runBlocking { HourlyOnDemandRun.run(Host(hours(72)) { throw CancellationException("gone") }, google, target, zone, now) }
    }

    @Test
    fun anEmptyFetchIsEmpty() = runBlocking {
        assertEquals(HourlyOnDemandRun.Outcome.EMPTY, HourlyOnDemandRun.run(Host(hours(72)) { false }, google, target, zone, now).outcome)
    }
}
