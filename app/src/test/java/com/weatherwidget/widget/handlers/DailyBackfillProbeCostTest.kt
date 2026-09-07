package com.weatherwidget.widget.handlers

import android.content.Context
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The daily view's history-repair probe reads observations only to decide whether to enqueue a
 * background worker — it draws none of them, and it ran **inside the click**.
 *
 * Measured 2026-09-07 on SM-F936U1: a 72h unscoped read of 37,511 rows, 486-492ms warm, 3,185ms
 * under a concurrent sync and once 8,325ms, on every NWS daily paint. It was the entire reason an
 * NWS tap cost ~350ms more than the same tap on any other source, because
 * [DailyViewHandler.shouldProbeHistoryBackfill] gates on NWS.
 *
 * Both defences are pinned here, because either one alone leaves most of the cost:
 *  1. the shared cooldown is consulted BEFORE the read, so a paint inside the cooldown window reads
 *     nothing at all (the CLOUD probe has always done this; the daily probe did not);
 *  2. when it does read, it is scoped to the apis the coverage check keeps.
 *
 * See performance/260907-daily-path-observation-reads-unscoped-and-duplicated.md.
 */
@Category(ShortDuration::class)
class DailyBackfillProbeCostTest {

    private val lat = 37.4168205
    private val lon = -122.0890350
    private val now = LocalDateTime.of(2026, 9, 7, 5, 49)

    private fun stateManager(coolingDown: Boolean) = mockk<WidgetStateManager>(relaxed = true).also {
        // shouldRefreshMissingActuals == false means "still cooling down".
        every { it.shouldRefreshMissingActuals(any(), any(), any()) } returns !coolingDown
        // Unanchored, so the enqueue path stops right after the read: this test is about what the
        // probe COSTS, and resolveBackfillLocation's own behaviour is covered elsewhere.
        every { it.getStoredWidgetLocation(any()) } returns null
    }

    @Test
    fun `a paint inside the cooldown window reads no observations at all`() = runBlocking {
        val repository = mockk<WeatherRepository>(relaxed = true)

        DailyViewHandler.maybeBackfillIncompleteHistory(
            context = mockk<Context>(relaxed = true),
            database = mockk<WeatherDatabase>(relaxed = true),
            repository = repository,
            stateManager = stateManager(coolingDown = true),
            appWidgetId = 345,
            displaySource = WeatherSource.NWS,
            lat = lat,
            lon = lon,
            centerDate = now.toLocalDate(),
            today = now.toLocalDate(),
            now = now,
        )

        coVerify(exactly = 0) {
            repository.getObservationsInRange(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `a non-NWS source never probes, whatever the cooldown says`() = runBlocking {
        val repository = mockk<WeatherRepository>(relaxed = true)

        for (source in listOf(WeatherSource.OPEN_METEO, WeatherSource.SILURIAN, WeatherSource.TOMORROW_IO)) {
            DailyViewHandler.maybeBackfillIncompleteHistory(
                context = mockk<Context>(relaxed = true),
                database = mockk<WeatherDatabase>(relaxed = true),
                repository = repository,
                stateManager = stateManager(coolingDown = false),
                appWidgetId = 345,
                displaySource = source,
                lat = lat,
                lon = lon,
                centerDate = now.toLocalDate(),
                today = now.toLocalDate(),
                now = now,
            )
        }

        coVerify(exactly = 0) {
            repository.getObservationsInRange(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `when it does read, the read is scoped and spans the backfill window`() = runBlocking {
        val repository = mockk<WeatherRepository>(relaxed = true)
        val startSlot = slot<Long>()
        val endSlot = slot<Long>()
        val apisSlot = slot<Collection<String>?>()
        coEvery {
            repository.getObservationsInRange(
                capture(startSlot), capture(endSlot), any(), any(), captureNullable(apisSlot),
            )
        } returns emptyList()

        DailyViewHandler.maybeBackfillIncompleteHistory(
            context = mockk<Context>(relaxed = true),
            database = mockk<WeatherDatabase>(relaxed = true),
            repository = repository,
            stateManager = stateManager(coolingDown = false),
            appWidgetId = 345,
            displaySource = WeatherSource.NWS,
            lat = lat,
            lon = lon,
            centerDate = now.toLocalDate(),
            today = now.toLocalDate(),
            now = now,
        )

        coVerify(exactly = 1) {
            repository.getObservationsInRange(any(), any(), any(), any(), any())
        }
        assertEquals(
            "the probe must still cover the full backfill window",
            72L,
            (endSlot.captured - startSlot.captured) / 3_600_000L,
        )
        val apis = apisSlot.captured
        assertTrue("the probe read must be api-scoped, not apis=ALL", apis != null && apis.isNotEmpty())
        assertEquals(ActualsReadScope.apisFor(WeatherSource.NWS), apis!!.toSet())
    }

    /**
     * The gate itself, restated as a cost statement rather than a coverage one: browsing far enough
     * back that the repair window is off screen must not probe.
     */
    @Test
    fun `browsing past the repair window does not probe`() = runBlocking {
        val repository = mockk<WeatherRepository>(relaxed = true)
        val today = LocalDate.of(2026, 9, 7)

        DailyViewHandler.maybeBackfillIncompleteHistory(
            context = mockk<Context>(relaxed = true),
            database = mockk<WeatherDatabase>(relaxed = true),
            repository = repository,
            stateManager = stateManager(coolingDown = false),
            appWidgetId = 345,
            displaySource = WeatherSource.NWS,
            lat = lat,
            lon = lon,
            centerDate = today.minusDays(30),
            today = today,
            now = now,
        )

        coVerify(exactly = 0) {
            repository.getObservationsInRange(any(), any(), any(), any(), any())
        }
    }
}
