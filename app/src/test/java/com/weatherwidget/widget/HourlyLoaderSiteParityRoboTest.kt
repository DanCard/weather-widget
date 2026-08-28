package com.weatherwidget.widget

import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.handlers.GraphDataLoader
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The two hourly loaders must agree: given the same database and the same centre, neither may return
 * rows from a device site the centre has left.
 *
 * This is a **differential** test, not a regression guard for a known fix. On 2026-08-28 the widget
 * rendered a three-hour-old observation because the hourly list handed to the renderer was stamped
 * entirely at a site the configured location had left, and static tracing could not find which loader
 * produced it — every location source resolved to the configured location and the same-site filtering
 * looked correct everywhere. `WIDGET_PAINT` narrowed it to `origin=USER_INTERACTION`, which is
 * [GraphDataLoader.loadGraphWindowHourlyForecasts]; the sync path
 * ([HourlyForecastLoader.load]) painted correctly from the same database.
 *
 * So both are driven here from one database, at one centre, and compared.
 *
 * The seeded shape is the measured one. `HOURLY_LOAD` on the device reported:
 *
 * ```
 * currentSites=37.41700,-122.08900
 * historySites=37.41700,-122.08900|37.42200,-122.07300|…|37.40600,-122.02100
 * ```
 *
 * — one site through `current`, seven through `history`, because `loadGraphWindowHourlyForecasts`
 * filters `centerRows`/`nowRows` through [LocationMatch.sameSite] and leaves `historyRows`
 * unfiltered, relying on `HourlyForecastStitcher.collapse` downstream. History is therefore the input
 * most able to introduce a foreign site, and the cases below load it that way on purpose.
 *
 * See plans/260828-interaction-paint-loads-hourly-at-the-wrong-site.md.
 */
@Category(LongDuration::class)
class HourlyLoaderSiteParityRoboTest : RobolectricTest() {

    private lateinit var db: WeatherDatabase
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Where the user is: the configured centre, and where fetches are landing. */
    private val liveLat = 37.406
    private val liveLon = -122.021

    /** Where the user was: inside the ±0.1° read box, 0.068° away in longitude, no longer fetched. */
    private val staleLat = 37.417
    private val staleLon = -122.089

    private val source = WeatherSource.NWS.id
    private val zone: ZoneId = ZoneId.systemDefault()
    private val now: LocalDateTime = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)

    private fun hourMs(offset: Long): Long =
        now.plusHours(offset).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setup() {
        WeatherDatabase.setIsTesting(true)
        db = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(db)
    }

    @After
    fun tearDown() {
        db.close()
        WeatherDatabase.setIsTesting(false)
        // resetInstanceForTesting, not just close(): a closed singleton left in place poisons every
        // later test in the same fork with a misattributed failure.
        WeatherDatabase.resetInstanceForTesting()
    }

    private fun live(offset: Long, temp: Float = 70f) = row(liveLat, liveLon, offset, temp, fetchedAt = hourMs(0))

    private fun stale(offset: Long, temp: Float = 90f) =
        row(staleLat, staleLon, offset, temp, fetchedAt = hourMs(-6))

    private fun row(lat: Double, lon: Double, offset: Long, temp: Float, fetchedAt: Long) =
        HourlyForecastEntity(
            dateTime = hourMs(offset),
            locationLat = lat,
            locationLon = lon,
            temperature = temp,
            condition = "Clear",
            source = source,
            fetchedAt = fetchedAt,
        )

    private fun history(lat: Double, lon: Double, offset: Long, temp: Float, fetchedAt: Long) =
        HourlyForecastHistoryEntity(
            dateTime = hourMs(offset),
            locationLat = lat,
            locationLon = lon,
            temperature = temp,
            condition = "Clear",
            source = source,
            timestampToGroupPredictions = fetchedAt,
            fetchedAt = fetchedAt,
        )

    /** Distinct device sites in a result, for readable failure messages. */
    private fun sitesOf(rows: List<HourlyForecastEntity>): List<String> =
        rows.map { String.format("%.5f,%.5f", it.locationLat, it.locationLon) }.distinct().sorted()

    /**
     * Both loaders are judged in ONE assertion on purpose.
     *
     * Asserting them one after the other means JUnit stops at the first failure and the second
     * loader is never evaluated — which is worthless in a differential test, whose entire job is to
     * say *which* of the two leaks. The first run of this file did exactly that and looked like
     * evidence that the interaction path was clean when it had simply not been reached.
     */
    private fun assertNeitherLoaderLeaks(
        sync: List<HourlyForecastEntity>,
        interaction: List<HourlyForecastEntity>,
    ) {
        val complaints = listOf("sync path" to sync, "interaction path" to interaction).mapNotNull { (label, rows) ->
            val foreign = rows.filterNot {
                LocationMatch.sameSite(liveLat, liveLon, it.locationLat, it.locationLon)
            }
            if (foreign.isEmpty()) null else "$label leaked ${foreign.size} row(s); sites=${sitesOf(rows)}"
        }
        assertTrue(
            "centre=$liveLat,$liveLon abandoned=$staleLat,$staleLon :: ${complaints.joinToString(" | ")}",
            complaints.isEmpty(),
        )
    }

    private suspend fun loadViaSyncPath(): List<HourlyForecastEntity> =
        HourlyForecastLoader(context, WidgetStateManager(context))
            // Explicit sources: with no widgets bound, hourlySourceIds() is GENERIC_GAP only and
            // would filter out every seeded row, passing the test for the wrong reason.
            .load(lat = liveLat, lon = liveLon, sources = listOf(source), caller = "test_sync")

    private suspend fun loadViaInteractionPath(): List<HourlyForecastEntity> =
        GraphDataLoader.loadGraphWindowHourlyForecasts(
            hourlyDao = db.hourlyForecastDao(),
            hourlyHistoryDao = db.hourlyForecastHistoryDao(),
            lat = liveLat,
            lon = liveLon,
            centerTime = now,
            zoom = ZoomStage.WIDE.window(),
            now = now,
            source = WeatherSource.NWS,
        )

    /** Both sites fully covered — the ordinary case after a move, once the new site has been fetched. */
    @Test
    fun `neither loader returns the abandoned site when both are covered`() = runTest {
        val offsets = (-12L..12L).toList()
        db.hourlyForecastDao().insertAll(offsets.map { live(it) } + offsets.map { stale(it) })
        db.hourlyForecastHistoryDao().insertAll(
            offsets.map { history(liveLat, liveLon, it, 70f, hourMs(0)) } +
                offsets.map { history(staleLat, staleLon, it, 90f, hourMs(-6)) },
        )

        val sync = loadViaSyncPath()
        val interaction = loadViaInteractionPath()

        assertNeitherLoaderLeaks(sync, interaction)
        assertEquals(
            "the two loaders must agree on which site they read",
            sitesOf(sync),
            sitesOf(interaction),
        )
    }

    /**
     * The case a fallback would betray: hours the configured site does not cover, which only the
     * abandoned site can serve.
     *
     * Dropping the hour is correct. Filling it from a site 6 km away silently mixes two places into
     * one curve — and because that row then carries the foreign coordinate, a downstream
     * `firstOrNull()` on the list can adopt it as the render's location, which is how the observation
     * blend ended up centred three hours in the past.
     */
    @Test
    fun `an hour only the abandoned site covers is dropped, not borrowed`() = runTest {
        val liveOffsets = (-12L..-1L).toList()
        val staleOnlyOffsets = (0L..6L).toList()
        db.hourlyForecastDao().insertAll(
            liveOffsets.map { live(it) } + (liveOffsets + staleOnlyOffsets).map { stale(it) },
        )
        db.hourlyForecastHistoryDao().insertAll(
            (liveOffsets + staleOnlyOffsets).map { history(staleLat, staleLon, it, 90f, hourMs(-6)) },
        )

        val sync = loadViaSyncPath()
        val interaction = loadViaInteractionPath()

        assertNeitherLoaderLeaks(sync, interaction)
        assertTrue(
            "the stale-only hours must be absent, not borrowed: ${interaction.map { it.dateTime }}",
            interaction.none { it.dateTime in staleOnlyOffsets.map(::hourMs) },
        )
    }

    /**
     * The measured asymmetry: `current` holds one site, `history` holds several. `historyRows` reaches
     * the stitcher without a same-site pre-filter, so this is the shape most likely to leak.
     */
    @Test
    fun `a foreign site arriving only through history does not reach the renderer`() = runTest {
        val offsets = (-12L..12L).toList()
        db.hourlyForecastDao().insertAll(offsets.map { live(it) })
        db.hourlyForecastHistoryDao().insertAll(
            offsets.map { history(liveLat, liveLon, it, 70f, hourMs(-1)) } +
                offsets.map { history(staleLat, staleLon, it, 90f, hourMs(-6)) } +
                // The other five fragments the device actually reported, all within the ±0.1° box.
                offsets.map { history(37.422, -122.073, it, 91f, hourMs(-6)) } +
                offsets.map { history(37.416, -122.087, it, 92f, hourMs(-6)) } +
                offsets.map { history(37.424, -122.088, it, 93f, hourMs(-6)) } +
                offsets.map { history(37.418, -122.087, it, 94f, hourMs(-6)) } +
                offsets.map { history(37.419, -122.094, it, 95f, hourMs(-6)) },
        )

        val sync = loadViaSyncPath()
        val interaction = loadViaInteractionPath()

        assertNeitherLoaderLeaks(sync, interaction)
        assertEquals(
            "only the configured site may survive",
            listOf(String.format("%.5f,%.5f", liveLat, liveLon)),
            sitesOf(interaction),
        )
    }
}
