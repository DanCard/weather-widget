package com.weatherwidget.shared.nowcast

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder

/** [NowcastRefreshRun]: the one nowcast refresh rule, against a fake host. */
@Category(ShortDuration::class)
class NowcastRefreshRunTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L

    private inner class FakeHost(
        override val openMeteoDisplayed: Boolean = true,
        val pop: Int = 60,
        val rowSource: String = "OPEN_METEO",
        override val usableSourceIds: Set<String> = setOf("OPEN_METEO", "NWS"),
        val outcome: NowcastFetcher.Outcome = NowcastFetcher.Outcome.Fetched(slots = 8, probed = false),
    ) : NowcastRefreshRun.Host {
        override val lat = 37.4
        override val lon = -122.0
        override val store = NowcastStore(tmp.newFolder())
        var hourlyReads = 0
        var fetches = 0
        val logs = mutableListOf<String>()
        override suspend fun hourly(fromMs: Long, toMs: Long): List<HourlyForecast> {
            hourlyReads++
            return listOf(HourlyForecast(dateTime = now, temperature = 60f, condition = "Rain", precipProbability = pop, source = rowSource))
        }
        override suspend fun fetch(): NowcastFetcher.Outcome { fetches++; return outcome }
        override suspend fun log(tag: String, message: String, level: String) { logs += "$tag/$level" }
    }

    @Test
    fun notDisplayedSkipsWithoutReadingHourly() = runBlocking {
        val host = FakeHost(openMeteoDisplayed = false)
        assertNull(NowcastRefreshRun.run(host, "test", now))
        assertEquals(0, host.hourlyReads)
        assertEquals(0, host.fetches)
    }

    @Test
    fun dryNextThreeHoursSkips() = runBlocking {
        val host = FakeHost(pop = 10)
        assertNull(NowcastRefreshRun.run(host, "test", now))
        assertEquals(0, host.fetches)
        assertEquals(emptyList<String>(), host.logs)
    }

    @Test
    fun plausibleRainFetchesAndLogs() = runBlocking {
        val host = FakeHost()
        assertEquals(NowcastFetcher.Outcome.Fetched(8, false), NowcastRefreshRun.run(host, "test", now))
        // The fake fetch stores no series, so the summary reads none: "dry".
        assertEquals(listOf("NOWCAST_FETCH/INFO", "NOWCAST_SUMMARY/INFO"), host.logs)
    }

    @Test
    fun aFailureLogsWarnAndNoSummary() = runBlocking {
        val host = FakeHost(outcome = NowcastFetcher.Outcome.Failed("timeout"))
        NowcastRefreshRun.run(host, "test", now)
        assertEquals(listOf("NOWCAST_FETCH/WARN"), host.logs)
    }

    /** User, 2026-10-24: a source turned off (or the Generic filler) keeps stale rows; they do not count. */
    @Test
    fun rainFromASourceThatIsNotUsableDoesNotFetch() = runBlocking {
        val host = FakeHost(pop = 90, rowSource = "TOMORROW_IO")
        assertNull(NowcastRefreshRun.run(host, "test", now))
        assertEquals(0, host.fetches)
    }
}
