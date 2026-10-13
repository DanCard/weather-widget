package com.weatherwidget.desktop

import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.test.category.MediumDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.junit.experimental.categories.Category

/**
 * CPU guard for the actuals-processing update cycle.
 *
 * The 10-minute "current temp" loop calls [DesktopWeatherRepository.refreshObservations], which
 * persists the fetched latest readings, recomputes daily extremes from the stored observation
 * window, and reloads the cache (re-resolving the current temperature). This test measures the
 * **CPU time** that cycle consumes against a realistically-populated database.
 *
 * CPU time, not wall time, is the metric by design: a busy machine that makes the test run slowly
 * does not inflate it the way it inflates wall time. Only the threads that run the cycle are
 * counted ([workerCpuTimeNanos]); whole-process time also counted JIT, GC and leftover threads.
 * It is the same signal `~/bin/sys-logging.sh`'s `top` samples were showing — the reason this
 * latest-only change exists (plans/260820-desktop-observation-loop-latest-only.md).
 */
@Category(MediumDuration::class)
class DesktopObservationCpuTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var repository: DesktopWeatherRepository
    private val weatherService = mockk<WeatherApiClient>()

    private val lat = 37.4220
    private val lon = -122.0841
    private val source = "NWS"

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-obs-cpu-test", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
        repository = DesktopWeatherRepository(weatherService, dao, lat, lon, source)

        val now = (System.currentTimeMillis() / 3600_000L) * 3600_000L
        seedSteadyState(now)

        // The service returns the ~5 latest readings the latest-only fetch produces (one per
        // station); the network/parse half is mocked out so this test isolates the processing
        // pipeline. The fetch-size invariant itself is pinned by DesktopObservationLatestOnlyTest.
        coEvery { weatherService.fetchFeedObservations(any(), recentOnly = true) } returns RawFetch(
            rawObservations = (0 until 5).map { latestReading(it, now) },
        )
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    /**
     * Populates the DB the way it looks mid-life: ~1 000 historical observations across 5 stations
     * (what prior full-forecast pulls have already stored), plus hourly and daily forecasts, so
     * recomputeDailyExtremes and loadCached do real, representative work rather than walking an
     * empty database.
     */
    private fun seedSteadyState(now: Long) {
        val hourMs = 3600_000L
        val dayMs = 24 * hourMs

        val hourly = (-24..24).map { h ->
            HourlyForecast(now + h * hourMs, 65f, "Clear", source = source, fetchedAt = now)
        }
        dao.upsertHourlyForecasts(lat, lon, source, hourly)

        val today = LocalDate.now()
        val daily = (0..6).map { d ->
            DailyForecast(today.plusDays(d.toLong()).toString(), 72f, 55f, "Clear", source = source)
        }
        dao.upsertForecasts(lat, lon, source, daily)

        // 5 stations × 200 readings over the past 7 days (~50-minute spacing).
        val observations = mutableListOf<DesktopObservationEntity>()
        for (station in 0 until 5) {
            for (i in 0 until 200) {
                val ts = now - (7L * dayMs) + i * (7L * dayMs / 200)
                observations.add(
                    DesktopObservationEntity(
                        stationId = "STATION_$station",
                        stationName = "Station $station",
                        timestamp = ts,
                        temperature = 60f + (i % 20),
                        condition = "Clear",
                        locationLat = lat,
                        locationLon = lon,
                        distanceKm = station.toFloat(),
                        stationType = StationType.OFFICIAL,
                        fetchedAt = now,
                        api = source,
                    ),
                )
            }
        }
        dao.upsertObservations(observations)
    }

    private fun latestReading(station: Int, now: Long) = ObservationReading(
        stationId = "STATION_$station",
        stationName = "Station $station",
        timestamp = now - station,
        temperature = 66f + station,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        distanceKm = station.toFloat(),
        stationType = StationType.OFFICIAL,
        api = source,
    )

    private val threadBean = java.lang.management.ManagementFactory.getThreadMXBean()

    /**
     * CPU time of the threads that run the cycle: this test thread and the coroutine pool
     * (`Dispatchers.IO` shares `DefaultDispatcher-worker-*` threads with Default). Process CPU time
     * also counted the JIT compilers, GC and coroutines left running by earlier test classes in
     * this JVM, and failed the full suite at 502 ms against a cycle measured at ~165 ms alone.
     */
    private fun workerCpuTimeNanos(): Long {
        val testThread = Thread.currentThread().name
        return threadBean.allThreadIds.sumOf { id ->
            val name = threadBean.getThreadInfo(id)?.threadName ?: return@sumOf 0L
            if (name == testThread || name.startsWith("DefaultDispatcher-worker")) {
                threadBean.getThreadCpuTime(id).coerceAtLeast(0L)
            } else {
                0L
            }
        }
    }

    private fun processCpuTimeNanos(): Long {
        val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        val cpu = (bean as? com.sun.management.OperatingSystemMXBean)?.processCpuTime ?: return -1L
        return cpu
    }

    @Test
    fun `refreshObservations cycle consumes bounded CPU time`() = runTest {
        assumeTrue("process CPU time is unavailable on this JVM", processCpuTimeNanos() >= 0)

        // Warm up: JIT compilation + one-time backfills (chance/frozen-column markers) land here,
        // not in the measured window.
        repeat(3) { repository.refreshObservations() }

        val iterations = 5
        val processStart = processCpuTimeNanos()
        val start = workerCpuTimeNanos()
        repeat(iterations) { repository.refreshObservations() }
        val cpuMsPerIteration = (workerCpuTimeNanos() - start) / iterations / 1_000_000L
        val processMsPerIteration = (processCpuTimeNanos() - processStart) / iterations / 1_000_000L

        println(
            "DIAGNOSTIC: refreshObservations CPU per iteration = ${cpuMsPerIteration}ms " +
                "(whole process ${processMsPerIteration}ms)",
        )

        // Generous bound. The cycle's own threads measured ~35 ms alone and ~68 ms with the full
        // suite running beside it (hyperthread siblings busy, 2026-10-13); the bound exists to catch
        // a real regression (e.g. re-introducing a ~2 500-row fetch/upsert or an O(n²) recompute),
        // not to benchmark. CPU time is not fully immune to load — a busy sibling hyperthread makes
        // the same work cost more — hence the headroom.
        assertTrue(
            "refreshObservations used ${cpuMsPerIteration}ms CPU per iteration, exceeding 500ms",
            cpuMsPerIteration < 500,
        )
    }
}
