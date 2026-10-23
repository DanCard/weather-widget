package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** [PriorDayCloudRun]: the one prior-day cloud fetch rule, against a fake host. */
@Category(ShortDuration::class)
class PriorDayCloudRunTest {

    private class FakeHost(var reply: () -> Map<Long, Int> = { mapOf(1L to 40) }) : PriorDayCloudRun.Host {
        override var lastAttemptMs = 0L
        val fetches = mutableListOf<Int>()
        val stored = mutableListOf<Map<Long, Int>>()
        val logs = mutableListOf<String>()
        override suspend fun fetch(pastDays: Int, nowMs: Long): Map<Long, Int> { fetches += pastDays; return reply() }
        override suspend fun store(coverByHour: Map<Long, Int>, nowMs: Long) { stored += coverByHour }
        override suspend fun log(tag: String, message: String, level: String) { logs += "$tag/$level" }
    }

    private val om = WeatherSource.OPEN_METEO.id
    private val hour = PriorDayCloudRun.INTERVAL_MS

    @Test
    fun onlyOpenMeteoHasAPreviousRunsProduct() = runBlocking {
        val host = FakeHost()
        PriorDayCloudRun.run(host, WeatherSource.GOOGLE_WEATHER.id, nowMs = 10 * hour)
        assertEquals(emptyList<Int>(), host.fetches)
        assertEquals(emptyList<String>(), host.logs) // not an hourly "empty" warning for every other source
    }

    @Test
    fun storesAndLogsThenWaitsAnHour() = runBlocking {
        val host = FakeHost()
        PriorDayCloudRun.run(host, om, nowMs = 10 * hour)
        assertEquals(listOf(PriorDayCloudRun.PAST_DAYS), host.fetches)
        assertEquals(1, host.stored.size)
        assertEquals(listOf("PRIOR_CLOUD/INFO"), host.logs)
        PriorDayCloudRun.run(host, om, nowMs = 11 * hour - 1)
        assertEquals(1, host.fetches.size)
        PriorDayCloudRun.run(host, om, nowMs = 11 * hour)
        assertEquals(2, host.fetches.size)
    }

    @Test
    fun anEmptyReplyIsLoudAndStillCountsAsTheAttempt() = runBlocking {
        val host = FakeHost(reply = { emptyMap() })
        PriorDayCloudRun.run(host, om, nowMs = 10 * hour)
        PriorDayCloudRun.run(host, om, nowMs = 10 * hour + 1)
        assertEquals(1, host.fetches.size)
        assertEquals(0, host.stored.size)
        assertEquals(listOf("PRIOR_CLOUD_EMPTY/WARN"), host.logs)
    }

    @Test
    fun aFailureIsLoggedNotThrownAndCountsAsTheAttempt() = runBlocking {
        val host = FakeHost(reply = { throw java.io.IOException("down") })
        PriorDayCloudRun.run(host, om, nowMs = 10 * hour)
        PriorDayCloudRun.run(host, om, nowMs = 10 * hour + 1)
        assertEquals(1, host.fetches.size)
        assertEquals(listOf("PRIOR_CLOUD_FAIL/WARN"), host.logs)
    }
}
