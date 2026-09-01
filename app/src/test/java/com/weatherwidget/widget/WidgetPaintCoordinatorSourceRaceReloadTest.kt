package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Regression cover for the hourly source-race repair (`WidgetPaintCoordinator.resolveEffectiveHourly`).
 *
 * The repair existed since 2026-08-08 but was a single check-then-reload. The reload is itself a
 * ~1s query on the Fold, and on 2026-09-01 a source toggle landed *inside* that window: the repair
 * caught up to SILURIAN at 06:49:18.76, the user selected TOMORROW_IO at 06:49:19.57, and the paint
 * 0.24s later drew "Cloud data unavailable" over a graph that was already correct.
 *
 * See plans/260901-stale-source-paint-clobbers-hourly-graph.md.
 */
@Category(ShortDuration::class)
class WidgetPaintCoordinatorSourceRaceReloadTest {

    private val widgetId = 345
    private val lat = 37.41674
    private val lon = -122.08884

    private val generic = WeatherSource.GENERIC_GAP.id
    private val nws = WeatherSource.NWS.id

    private val stateManager: WidgetStateManager = mockk(relaxed = true)
    private val loader: HourlyForecastLoader = mockk(relaxed = true)
    private val appLogDao: AppLogDao = mockk(relaxed = true)

    private val coordinator = WidgetPaintCoordinator(
        context = mockk<Context>(relaxed = true),
        weatherRepository = mockk<WeatherRepository>(relaxed = true),
        widgetStateManager = stateManager,
        appLogDao = appLogDao,
        hourlyForecastLoader = loader,
        dataBundleLoader = mockk(relaxed = true),
        gpsResampler = mockk(relaxed = true),
    )

    /** One row tagged with its source, so a returned list is traceable to the load that produced it. */
    private fun rowsFor(source: String) = listOf(
        HourlyForecastEntity(
            dateTime = 1_788_270_000_000L,
            locationLat = lat,
            locationLon = lon,
            temperature = 61.2f,
            condition = "Cloudy",
            source = source,
            cloudCover = 88,
            fetchedAt = 1_788_270_000_000L,
        ),
    )

    /** The widget's display source as read at each successive check. */
    private fun displaySourceReads(vararg sources: WeatherSource) {
        every { stateManager.getCurrentDisplaySource(widgetId) } returnsMany sources.toList()
    }

    private fun resolve(
        loadedHourlySourceIds: List<String>,
        hourlyForecasts: List<HourlyForecastEntity>,
    ) = runBlocking {
        coordinator.resolveEffectiveHourly(
            appWidgetIds = intArrayOf(widgetId),
            loadedHourlySourceIds = loadedHourlySourceIds,
            lat = lat,
            lon = lon,
            hourlyForecasts = hourlyForecasts,
        )
    }

    @Test
    fun `a covered source does no extra query`() {
        // The overwhelmingly common path. Pairs with the reload cases below so a broken predicate
        // fails in both directions rather than passing vacuously.
        displaySourceReads(WeatherSource.NWS)
        val original = rowsFor(nws)

        val result = resolve(loadedHourlySourceIds = listOf(nws, generic), hourlyForecasts = original)

        assertEquals("Steady source must return the caller's rows untouched", original, result)
        coVerify(exactly = 0) { loader.load(any(), any(), any(), any()) }
    }

    @Test
    fun `a single toggle during the fetch reloads once`() {
        // Source moved NWS -> SILURIAN while the worker fetched. One reload covers it, and the
        // re-check then finds SILURIAN present and stops.
        displaySourceReads(WeatherSource.SILURIAN, WeatherSource.SILURIAN)
        val reloaded = rowsFor(WeatherSource.SILURIAN.id)
        coEvery { loader.load(lat, lon, any(), any()) } returns reloaded

        val result = resolve(listOf(nws, generic), rowsFor(nws))

        assertEquals("The reloaded rows must replace the stale ones", reloaded, result)
        coVerify(exactly = 1) { loader.load(lat, lon, any(), any()) }
    }

    @Test
    fun `a toggle landing inside the reload triggers a second reload`() {
        // THE 2026-09-01 BUG. Check 1 sees SILURIAN and reloads for it; the user selects TOMORROW_IO
        // while that query runs; check 2 must notice and reload again. The old single-shot repair
        // returned the SILURIAN rows here and the paint drew an empty graph for TOMORROW_IO.
        displaySourceReads(WeatherSource.SILURIAN, WeatherSource.TOMORROW_IO)
        val silurianRows = rowsFor(WeatherSource.SILURIAN.id)
        val tomorrowRows = rowsFor(WeatherSource.TOMORROW_IO.id)
        val requestedScopes = mutableListOf<List<String>>()
        val scope = slot<List<String>>()
        coEvery { loader.load(lat, lon, capture(scope), any()) } answers {
            requestedScopes.add(scope.captured)
            if (scope.captured.contains(WeatherSource.TOMORROW_IO.id)) tomorrowRows else silurianRows
        }

        val result = resolve(listOf(nws, generic), rowsFor(nws))

        assertEquals(
            "The rows handed to the paint must cover the source that is on screen NOW, " +
                "not the one that was on screen when the first reload started",
            tomorrowRows,
            result,
        )
        assertEquals("Expected exactly two reloads, got scopes=$requestedScopes", 2, requestedScopes.size)
        assertEquals(
            "The second reload must be scoped to the newly selected source, got $requestedScopes",
            listOf(WeatherSource.TOMORROW_IO.id, generic),
            requestedScopes[1],
        )
    }

    @Test
    fun `reloads are bounded when the source never settles`() {
        // A source that changes on every read must not spin the paint. The bound is what makes the
        // loop safe; WidgetRenderer.shouldSkipStaleSourcePaint absorbs whatever is still uncovered.
        displaySourceReads(
            WeatherSource.SILURIAN,
            WeatherSource.TOMORROW_IO,
            WeatherSource.OPEN_METEO,
            WeatherSource.NWS,
        )
        coEvery { loader.load(lat, lon, any(), any()) } returns rowsFor(nws)

        resolve(listOf(nws, generic), rowsFor(nws))

        coVerify(exactly = WidgetPaintCoordinator.MAX_HOURLY_SOURCE_RACE_RELOADS) {
            loader.load(lat, lon, any(), any())
        }
    }

    @Test
    fun `an empty reload keeps the original rows`() {
        // A transient DB miss must never blank every widget's hourly graph, and must not burn the
        // second attempt re-asking the same question.
        displaySourceReads(WeatherSource.TOMORROW_IO, WeatherSource.TOMORROW_IO)
        val original = rowsFor(nws)
        coEvery { loader.load(lat, lon, any(), any()) } returns emptyList()

        val result = resolve(listOf(nws, generic), original)

        assertEquals("An empty repair reload must leave the caller's rows standing", original, result)
        coVerify(exactly = 1) { loader.load(lat, lon, any(), any()) }
    }

    @Test
    fun `no location means no reload`() {
        // Nothing to scope a query to; the paint path handles the no-location state separately.
        displaySourceReads(WeatherSource.TOMORROW_IO)
        val original = rowsFor(nws)

        val result = runBlocking {
            coordinator.resolveEffectiveHourly(
                appWidgetIds = intArrayOf(widgetId),
                loadedHourlySourceIds = listOf(nws, generic),
                lat = null,
                lon = null,
                hourlyForecasts = original,
            )
        }

        assertEquals(original, result)
        coVerify(exactly = 0) { loader.load(any(), any(), any(), any()) }
    }
}
