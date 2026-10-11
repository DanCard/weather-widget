package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.sql.DriverManager

/**
 * The screen-wake catch-up judges the observations' own freshness ([DesktopWeatherDao.getLastSuccessfulObservationFetch]:
 * `OBS_REFRESH` / `REFRESH` rows), never the forecast's `REFRESH` age alone, then asks the shared rule
 * ([DesktopFetchStrategy.shouldCatchUpOnKick] → `CurrentTempCadence`).
 * performance/261011-unify-current-temp-cadence-shared.md
 */
@Category(ShortDuration::class)
class DesktopObservationCatchUpTest {
    private val dbPath = Files.createTempFile("weather-obs-catchup-test", ".db")
    private val dao = DesktopWeatherDao(DesktopWeatherDatabase(dbPath).apply { initialize() })
    private val minute = 60_000L
    private val now = System.currentTimeMillis()
    private val source = "OPEN_METEO"

    @After
    fun teardown() {
        Files.deleteIfExists(dbPath)
    }

    private fun logRow(tag: String, atMs: Long) =
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { c ->
            c.createStatement().use {
                it.execute("INSERT INTO app_logs (timestamp, level, tag, message) VALUES ($atMs, 'INFO', '$tag', 'source=$source obs=10')")
            }
        }

    private fun wakeCatchesUp() =
        DesktopFetchStrategy.shouldCatchUpOnKick("screensaver:wake", dao.getLastSuccessfulObservationFetch(source), false, 85, now)

    @Test
    fun `a fresh observation fetch beside an old forecast refresh does not catch up`() {
        logRow("REFRESH", now - 3 * 60 * minute)
        logRow("OBS_REFRESH", now - 5 * minute)
        assertFalse(wakeCatchesUp())
    }

    @Test
    fun `observations 20 min old catch up on wake`() {
        logRow("REFRESH", now - 3 * 60 * minute)
        logRow("OBS_REFRESH", now - 25 * minute)
        assertTrue(wakeCatchesUp())
    }
}
