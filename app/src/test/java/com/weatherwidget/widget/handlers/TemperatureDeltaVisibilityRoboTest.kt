package com.weatherwidget.widget.handlers

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
 * temp minus the blended actual at the same clock time 24h earlier), sourced from
 * [WeatherRepository.getObservationsInRange]. It is pan-independent — visible whenever the delta
 * exists and clears the 0.1° noise threshold, regardless of where the graph window is scrolled.
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
        lastObservedTemp: Float?,
        repository: WeatherRepository?,
        centerTime: LocalDateTime = now,
        observedAtMs: Long = epochMs(now),
    ): TemperatureWidgetState {
        val dimensions = WidgetDimensions(cols = 4, rows = 2, widthDp = 300, heightDp = 180, isIconWidth = false)

        val result = TemperatureStateResolver.resolve(
            context = context,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourly,
            currentTempHourlyForecasts = hourly,
            centerTime = centerTime,
            displaySource = WeatherSource.NWS,
            precipProbability = 0,
            lastObservedTemp = lastObservedTemp,
            observedAt = observedAtMs,
            dimensions = dimensions,
            stateManager = stateManager,
            repository = repository,
            deferCurrentTempResolution = false
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

    /** Repository whose only observation is [yesterdayTemp] exactly 24h before [observedAtMs]. */
    private fun repositoryWithYesterdayObservation(yesterdayTemp: Float, observedAtMs: Long): WeatherRepository {
        val repository = mockk<WeatherRepository>()
        val observation = ObservationEntity(
            stationId = "TST",
            stationName = "Test Station",
            timestamp = observedAtMs - 24L * 60 * 60 * 1000,
            temperature = yesterdayTemp,
            condition = "observed",
            locationLat = 37.0,
            locationLon = -122.0,
            distanceKm = 1f,
            stationType = "OFFICIAL",
            api = WeatherSource.NWS.id,
        )
        val read = observationReadOf(listOf(observation))
        coEvery { repository.getObservationsInRange(any(), any(), any(), any()) } returns listOf(observation)
        coEvery { repository.readObservationsInRange(any(), any(), any(), any()) } returns read
        return repository
    }

    @Test
    fun `delta badge is visible and red for positive delta`() = runBlocking {
        val now = LocalDateTime.now()
        val observedAtMs = epochMs(now)
        // Yesterday at this time: 70.0. Now: 71.2 -> +1.2 from yesterday.
        val state = resolveState(
            hourlyCovering(now), now, 71.2f,
            repositoryWithYesterdayObservation(70.0f, observedAtMs),
            observedAtMs = observedAtMs,
        )

        assertTrue("Delta badge should be VISIBLE", state.header.isDeltaVisible)
        assertEquals("+1.2", state.header.deltaText)
        assertEquals("Should be red for positive", Color.parseColor("#FF6B35"), state.header.deltaColor)
    }

    @Test
    fun `delta badge is red for negative delta`() = runBlocking {
        val now = LocalDateTime.now()
        val observedAtMs = epochMs(now)
        // Yesterday at this time: 70.0. Now: 69.1 -> -0.9 from yesterday.
        val state = resolveState(
            hourlyCovering(now), now, 69.1f,
            repositoryWithYesterdayObservation(70.0f, observedAtMs),
            observedAtMs = observedAtMs,
        )

        assertTrue("Delta badge should be VISIBLE", state.header.isDeltaVisible)
        assertEquals("-0.9", state.header.deltaText)
        assertEquals("Should be red for negative", Color.parseColor("#FF6B35"), state.header.deltaColor)
    }

    @Test
    fun `delta badge is hidden when delta is below threshold`() = runBlocking {
        val now = LocalDateTime.now()
        val observedAtMs = epochMs(now)
        // Yesterday at this time: 70.0. Now: 70.05 -> +0.05, below the 0.1 noise threshold.
        val state = resolveState(
            hourlyCovering(now), now, 70.05f,
            repositoryWithYesterdayObservation(70.0f, observedAtMs),
            observedAtMs = observedAtMs,
        )

        assertFalse("Delta badge should be hidden for negligible delta", state.header.isDeltaVisible)
    }

    @Test
    fun `delta badge is hidden when no yesterday observation exists`() = runBlocking {
        val now = LocalDateTime.now()
        val repository = mockk<WeatherRepository>()
        coEvery { repository.getObservationsInRange(any(), any(), any(), any()) } returns emptyList()
        coEvery { repository.readObservationsInRange(any(), any(), any(), any()) } returns observationReadOf(emptyList())

        val state = resolveState(hourlyCovering(now), now, 71.2f, repository)

        assertFalse("Delta badge should be hidden without a yesterday observation", state.header.isDeltaVisible)
        assertEquals(null, state.header.deltaText)
    }

    // Post-swap contract: the yesterday delta is pan-independent, so it stays visible wherever the
    // graph window is scrolled (the old HeaderDeltaGate hid it once fully in the past).
    @Test
    fun `delta badge stays visible when scrolled into the future`() = runBlocking {
        val now = LocalDateTime.now()
        val observedAtMs = epochMs(now)

        val state = resolveState(
            hourlyCovering(now), now, 72.0f,
            repositoryWithYesterdayObservation(70.0f, observedAtMs),
            centerTime = now.plusHours(24),
            observedAtMs = observedAtMs,
        )

        assertTrue("Delta badge should stay visible when scrolled into the future", state.header.isDeltaVisible)
        assertEquals("+2.0", state.header.deltaText)
    }

    @Test
    fun `delta badge stays visible when scrolled fully into the past`() = runBlocking {
        val now = LocalDateTime.now()
        val observedAtMs = epochMs(now)

        // 48h back is fully past even at the widest zoom's forward span; the header delta still shows.
        val state = resolveState(
            hourlyCovering(now), now, 72.0f,
            repositoryWithYesterdayObservation(70.0f, observedAtMs),
            centerTime = now.minusHours(48),
            observedAtMs = observedAtMs,
        )

        assertTrue("Delta badge should stay visible once the window is fully in the past", state.header.isDeltaVisible)
        assertEquals("+2.0", state.header.deltaText)
    }
}
