package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.handlers.GraphDataLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * THE regression guard for the today-column `-13.7 fcst` delta
 * (plans/260806-today-column-stale-fragment-delta-opus.md).
 *
 * Proven-to-fail: before the fix, `HourlyForecastLoader` returned 81.3 degF for the 19:00 hour (a
 * forecast fetched 2026-07-24) while `GraphDataLoader` returned 66.6 degF (that day's 19:23 fetch) —
 * two loaders, one database, two answers. The widget alternated between `-13.7` and `+0.5` depending
 * on which loader rendered last.
 *
 * This runs against REAL Room/SQLite rather than a hand-built list, deliberately. The tie-break that
 * selected the stale row is a SQLite behaviour — `ORDER BY dateTime ASC` falling through to
 * `index_hourly_forecasts_locationLat_locationLon` (ascending latitude) — so a pure JVM test that
 * hand-orders the list encodes that observation as a *premise* instead of verifying it. See
 * notes/260710-fragmentation-test-strategy-robolectric-vs-instrumented.md for why this family gets a
 * Robolectric parity test and no instrumented test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class HourlyLoaderStaleFragmentParityRoboTest {

    private lateinit var db: WeatherDatabase
    private lateinit var context: Context

    /** The raw configured centre the widget queries with — NOT pre-quantized. */
    private val centerLat = 37.41681671142578
    private val centerLon = -122.08899688720703

    private val source = WeatherSource.OPEN_METEO

    private lateinit var now: LocalDateTime
    private var hour1900: Long = 0
    private var hour2000: Long = 0

    @Before
    fun setup() {
        ShadowLog.stream = System.out // Robolectric drops Log output otherwise, hiding loader errors.
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(db)

        // Anchor on a fixed "today" so the loader's now +/- 72/168h window always covers the fixture.
        now = LocalDateTime.now().withHour(19).withMinute(30).withSecond(0).withNano(0)
        hour1900 = epochMs(now.withMinute(0))
        hour2000 = hour1900 + 60 * 60 * 1000L

        runBlocking { db.hourlyForecastDao().insertAll(fragments()) }
    }

    @After
    fun tearDown() {
        WeatherDatabase.resetInstanceForTesting()
    }

    private fun epochMs(time: LocalDateTime): Long =
        time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun daysAgo(days: Long): Long = epochMs(now.minusDays(days))

    private fun row(lat: Double, lon: Double, time: Long, temp: Float, fetched: Long) =
        HourlyForecastEntity(
            dateTime = time,
            locationLat = lat,
            locationLon = lon,
            temperature = temp,
            condition = "Clear",
            source = source.id,
            fetchedAt = fetched,
        )

    /**
     * The eight real coordinate fragments captured from the device — a phone that had not moved for
     * days, fragmented purely by GPS jitter. Only 37.417,-122.089 is still refreshed; the rest are
     * frozen long-range forecasts (Open-Meteo returns 14 days ahead).
     */
    private fun fragments(): List<HourlyForecastEntity> = listOf(
        // site,      lon,       19:00, 20:00, fetched
        Triple(37.377 to -122.075, 80.6f to 76.8f, daysAgo(7)),
        Triple(37.417 to -122.089, 66.6f to 63.1f, epochMs(now.withMinute(23))), // live site, today
        Triple(37.419 to -122.087, 81.3f to 76.7f, daysAgo(13)), // the culprit
        Triple(37.420 to -122.095, 79.9f to 75.7f, daysAgo(8)),
        Triple(37.422 to -122.087, 81.4f to 76.6f, daysAgo(2)),
        Triple(37.422 to -122.073, 80.3f to 75.6f, daysAgo(3)),
        Triple(37.424 to -122.088, 81.5f to 76.7f, daysAgo(2)),
        Triple(37.481 to -122.184, 71.2f to 63.8f, daysAgo(10)),
    ).flatMap { (site, temps, fetched) ->
        listOf(
            row(site.first, site.second, hour1900, temps.first, fetched),
            row(site.first, site.second, hour2000, temps.second, fetched),
        )
    }

    private fun tempAt(rows: List<HourlyForecastEntity>, time: Long): Float? =
        rows.filter { it.dateTime == time && it.source == source.id }
            .also {
                assertTrue(
                    "collapse left ${it.size} rows for one hour — fragments were not collapsed: " +
                        it.joinToString { r -> "${r.temperature}@${r.locationLat},${r.locationLon}" },
                    it.size <= 1,
                )
            }
            .firstOrNull()?.temperature

    // ---- The invariant that broke --------------------------------------------------------------

    @Test
    fun `both loaders resolve the same 19_00 temperature from the same database`() = runBlocking {
        val viaHourlyLoader = HourlyForecastLoader(context, WidgetStateManager(context))
            .load(centerLat, centerLon, listOf(source.id))
        val viaGraphLoader = GraphDataLoader.loadGraphWindowHourlyForecasts(
            hourlyDao = db.hourlyForecastDao(),
            lat = centerLat,
            lon = centerLon,
            centerTime = now,
            zoom = ZoomLevel.entries.first(),
            now = now,
            source = source,
        )

        val hourly = tempAt(viaHourlyLoader, hour1900)
        val graph = tempAt(viaGraphLoader, hour1900)

        // Diagnostic kept permanently: printing both loaders' answers is what made the divergence
        // legible — the bug presented as a flapping widget, not as a failing assertion.
        val diag = "HourlyForecastLoader=$hourly GraphDataLoader=$graph " +
            "(fresh=66.6 from today's fetch, stale=81.3 from 13 days ago)"

        assertEquals("loaders disagree on the same DB; $diag", graph!!, hourly!!, 0.001f)
        assertEquals("stale 13-day-old fragment won in HourlyForecastLoader; $diag", 66.6f, hourly, 0.001f)
    }

    @Test
    fun `HourlyForecastLoader picks the freshest fragment for both hours`() = runBlocking {
        val rows = HourlyForecastLoader(context, WidgetStateManager(context))
            .load(centerLat, centerLon, listOf(source.id))

        assertEquals("19:00 must come from today's fetch", 66.6f, tempAt(rows, hour1900)!!, 0.001f)
        assertEquals("20:00 must come from today's fetch", 63.1f, tempAt(rows, hour2000)!!, 0.001f)
    }

    /**
     * The user-visible number, end to end through real SQLite. The observation is 65.3 degF at 19:30,
     * exactly midway between the two forecast hours.
     */
    @Test
    fun `interpolated forecast at the observation time gives a small delta, not -13_7`() = runBlocking {
        val rows = HourlyForecastLoader(context, WidgetStateManager(context))
            .load(centerLat, centerLon, listOf(source.id))

        val forecastAtObs = (tempAt(rows, hour1900)!! + tempAt(rows, hour2000)!!) / 2f
        val delta = 65.3f - forecastAtObs

        assertEquals("forecast at 19:30 should be today's curve", 64.85f, forecastAtObs, 0.01f)
        assertEquals(
            "appliedDelta regressed toward the stale-fragment value " +
                "(forecastAtObs=$forecastAtObs delta=$delta; the 2026-07-24 fragment gives 79.0 / -13.7)",
            0.45f,
            delta,
            0.01f,
        )
    }

    // ---- The premise the pure tests rest on ----------------------------------------------------

    /**
     * Verifies rather than assumes the SQLite tie-break: `HourlyStaleFragmentCollapseTest` hand-builds
     * its fixture in ascending-latitude order because that is what the DAO returns. If an index change
     * or Room upgrade ever alters that, this test says so loudly instead of letting the pure tests
     * drift silently out of correspondence with production.
     */
    @Test
    fun `raw DAO returns dateTime ties in ascending latitude, so the stale row arrives last`() = runBlocking {
        val raw = db.hourlyForecastDao()
            .getHourlyForecastsBySource(hour1900, hour1900, centerLat, centerLon, source.id)

        val lats = raw.map { it.locationLat }
        assertEquals(
            "DAO row order changed — the pure collapse fixture's ordering premise no longer holds. Got $lats",
            lats.sorted(),
            lats,
        )
        val freshIdx = raw.indexOfFirst { it.locationLat == 37.417 }
        val staleIdx = raw.indexOfFirst { it.locationLat == 37.419 }
        assertTrue(
            "the stale fragment must sort AFTER the fresh one — that ordering is what made a " +
                "last-wins associateBy pick it (fresh@$freshIdx stale@$staleIdx, order=$lats)",
            staleIdx > freshIdx,
        )
    }
}
