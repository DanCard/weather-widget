package com.weatherwidget.widget.handlers

import com.weatherwidget.data.model.StationType
import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.ObservationPoolDiagnostics
import com.weatherwidget.data.local.ObservationRangeRead
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

/**
 * Header delta visibility, post-swap: the header shows the DELTA FROM YESTERDAY (latest observed
 * temp minus the blended actual at the same clock time 24h earlier). It is taken with the
 * observation (`:shared` CurrentTempRun.observe via [CurrentTempResolver.observe]) and handed to
 * the view, so it is pan-independent — visible whenever the delta exists and clears the 0.1° noise
 * threshold, wherever the graph window is scrolled. The repository honours the requested range: the
 * graph's own observation read is centred on the graph, and the delta used to come from it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class TemperatureDeltaVisibilityRoboTest {
    private lateinit var context: Context
    private val appWidgetId = 77
    private lateinit var stateManager: WidgetStateManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(appWidgetId)
    }

    private suspend fun resolveState(
        hourly: List<HourlyForecastEntity>,
        now: LocalDateTime,
        repository: WeatherRepository,
        centerTime: LocalDateTime = now,
        heightDp: Int = 180,
    ): TemperatureWidgetState {
        val dimensions = WidgetDimensions(cols = 4, rows = 2, widthDp = 300, heightDp = heightDp, isIconWidth = false)
        // As WidgetRenderer / the interaction renderers: one observation per paint, then the view.
        val observation = CurrentTempResolver.observe(
            repository = repository,
            lat = 37.0,
            lon = -122.0,
            displaySource = WeatherSource.NWS,
            hourlyForecasts = hourly,
            now = now,
        )

        val result = TemperatureStateResolver.resolve(
            context = context,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourly,
            currentTempHourlyForecasts = hourly,
            centerTime = centerTime,
            displaySource = WeatherSource.NWS,
            precipProbability = 0,
            lastObservedTemp = observation?.temperature,
            observedAt = observation?.observedAt,
            deltaFromYesterday = observation?.deltaFromYesterday,
            dimensions = dimensions,
            stateManager = stateManager,
            repository = repository,
            deferCurrentTempResolution = false,
            now = now,
        )
        return result.state
    }

    private fun epochMs(dateTime: LocalDateTime): Long =
        dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Hourly forecast rows covering [now-72h, now+96h] so any panned graph window has data. */
    private fun hourlyCovering(now: LocalDateTime): List<HourlyForecastEntity> {
        val base = now.withMinute(0).withSecond(0).withNano(0)
        return (-72L..96L).map { offset ->
            HourlyForecastEntity(
                dateTime = epochMs(base.plusHours(offset)),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 70.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis()
            )
        }
    }

    private fun observationReadOf(rows: List<ObservationEntity>): ObservationRangeRead {
        val newest = rows.maxOfOrNull { it.timestamp }
        val diag = ObservationPoolDiagnostics.Summary(
            candidateCount = rows.size,
            mergedCount = rows.size,
            candidateNewestMs = newest,
            mergedNewestMs = newest,
            siteCount = if (rows.isEmpty()) 0 else 1,
            droppedFresherSites = emptyList(),
        )
        return ObservationRangeRead(rows, diag)
    }

    private fun observation(timestampMs: Long, temperature: Float) = ObservationEntity(
        stationId = "TST",
        stationName = "Test Station",
        timestamp = timestampMs,
        temperature = temperature,
        condition = "observed",
        locationLat = 37.0,
        locationLon = -122.0,
        distanceKm = 1f,
        stationType = StationType.OFFICIAL,
        api = WeatherSource.NWS.id,
    )

    /** Repository holding [rows]; every read returns only the rows inside its range. */
    private fun repositoryOf(rows: List<ObservationEntity>): WeatherRepository {
        val repository = mockk<WeatherRepository>()
        coEvery { repository.getObservationsInRange(any(), any(), any(), any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            rows.filter { it.timestamp in start..end }
        }
        coEvery { repository.readObservationsInRange(any(), any(), any(), any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            observationReadOf(rows.filter { it.timestamp in start..end })
        }
        return repository
    }

    /** One station: [currentTemp] at [now], [yesterdayTemp] exactly 24 h before. */
    private fun repositoryWithYesterday(now: LocalDateTime, currentTemp: Float, yesterdayTemp: Float): WeatherRepository {
        val nowMs = epochMs(now)
        return repositoryOf(
            listOf(
                observation(nowMs - 24L * 60 * 60 * 1000, yesterdayTemp),
                observation(nowMs, currentTemp),
            ),
        )
    }

    @Test
    fun `delta badge is visible and red for positive delta`() = runBlocking {
        val now = LocalDateTime.now()
        // Yesterday at this time: 70.0. Now: 71.2 -> +1.2 from yesterday.
        val state = resolveState(
            hourlyCovering(now), now, repositoryWithYesterday(now, currentTemp = 71.2f, yesterdayTemp = 70.0f),
        )

        assertTrue("Delta badge should be VISIBLE", state.header.isDeltaVisible)
        assertEquals("+1.2", state.header.deltaText)
        assertEquals("Should be red for positive", Color.parseColor("#FF6B35"), state.header.deltaColor)
    }

    @Test
    fun `delta badge is red for negative delta`() = runBlocking {
        val now = LocalDateTime.now()
        // Yesterday at this time: 70.0. Now: 69.1 -> -0.9 from yesterday.
        val state = resolveState(
            hourlyCovering(now), now, repositoryWithYesterday(now, currentTemp = 69.1f, yesterdayTemp = 70.0f),
        )

        assertTrue("Delta badge should be VISIBLE", state.header.isDeltaVisible)
        assertEquals("-0.9", state.header.deltaText)
        assertEquals("Should be red for negative", Color.parseColor("#FF6B35"), state.header.deltaColor)
    }

    @Test
    fun `delta badge is hidden when delta is below threshold`() = runBlocking {
        val now = LocalDateTime.now()
        // Yesterday at this time: 70.0. Now: 70.05 -> +0.05, below the 0.1 noise threshold.
        val state = resolveState(
            hourlyCovering(now), now, repositoryWithYesterday(now, currentTemp = 70.05f, yesterdayTemp = 70.0f),
        )

        assertFalse("Delta badge should be hidden for negligible delta", state.header.isDeltaVisible)
    }

    @Test
    fun `delta badge is hidden when no yesterday observation exists`() = runBlocking {
        val now = LocalDateTime.now()
        val repository = repositoryOf(listOf(observation(epochMs(now), 71.2f)))

        val state = resolveState(hourlyCovering(now), now, repository)

        assertFalse("Delta badge should be hidden without a yesterday observation", state.header.isDeltaVisible)
        assertEquals(null, state.header.deltaText)
    }

    // Post-swap contract: the yesterday delta is pan-independent, so it stays visible wherever the
    // graph window is scrolled (the old HeaderDeltaGate hid it once fully in the past).
    @Test
    fun `delta badge stays visible when scrolled into the future`() = runBlocking {
        val now = LocalDateTime.now()

        val state = resolveState(
            hourlyCovering(now), now, repositoryWithYesterday(now, currentTemp = 72.0f, yesterdayTemp = 70.0f),
            centerTime = now.plusHours(24),
        )

        assertTrue("Delta badge should stay visible when scrolled into the future", state.header.isDeltaVisible)
        assertEquals("+2.0", state.header.deltaText)
    }

    @Test
    fun `delta badge stays visible when scrolled fully into the past`() = runBlocking {
        val now = LocalDateTime.now()

        // 48h back is fully past even at the widest zoom's forward span; the header delta still shows.
        val state = resolveState(
            hourlyCovering(now), now, repositoryWithYesterday(now, currentTemp = 72.0f, yesterdayTemp = 70.0f),
            centerTime = now.minusHours(48),
        )

        assertTrue("Delta badge should stay visible once the window is fully in the past", state.header.isDeltaVisible)
        assertEquals("+2.0", state.header.deltaText)
    }

    // The graph's observation read spans centre − 72 h … + 60 h; panned 3 days ahead it no longer
    // reaches now − 24 h, and the delta read from it went blank.
    @Test
    fun `delta badge stays visible when the graph window no longer reaches yesterday`() = runBlocking {
        val now = LocalDateTime.now()

        val state = resolveState(
            hourlyCovering(now), now, repositoryWithYesterday(now, currentTemp = 72.0f, yesterdayTemp = 70.0f),
            centerTime = now.plusHours(72),
        )

        assertTrue("Delta badge should stay visible when panned past the graph read's reach", state.header.isDeltaVisible)
        assertEquals("+2.0", state.header.deltaText)
    }

    // Text mode's read was centre − 30 h … + 2 h, so a one-day pan dropped yesterday.
    @Test
    fun `text mode delta badge stays visible when panned a day ahead`() = runBlocking {
        val now = LocalDateTime.now()

        val state = resolveState(
            hourlyCovering(now), now, repositoryWithYesterday(now, currentTemp = 72.0f, yesterdayTemp = 70.0f),
            centerTime = now.plusHours(24),
            heightDp = 40,
        )

        assertFalse("Text mode (no graph)", state.graph.useGraph)
        assertTrue("Text-mode delta badge should stay visible a day ahead", state.header.isDeltaVisible)
        assertEquals("+2.0", state.header.deltaText)
    }
}
