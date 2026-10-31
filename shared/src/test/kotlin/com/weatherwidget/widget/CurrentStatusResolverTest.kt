package com.weatherwidget.widget

import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class CurrentStatusResolverTest {

    @Test
    fun `resolve packages the resolved temp with identity and forecast metadata`() {
        val resolver = CurrentStatusResolver(
            latitude = 37.4220,
            longitude = -122.0841,
            source = "NWS",
            resolveTemp = { _, _ ->
                CurrentTempRun.Result(
                    displayTemp = 66.79f,
                    appliedDelta = 4.08f,
                    deltaFromYesterday = 1.3f,
                    observedTemp = 66.0f,
                    observedAt = 1_780_000_000_000L,
                    windowObservations = 5,
                    windowHourly = 16,
                )
            },
        )

        val forecast = ForecastSnapshot(
            sourceId = "NWS",
            raw = RawFetch(),
            resolved = ResolvedView(
                currentObservedAt = 1_780_000_000_000L,
                currentCondition = "Clear",
            ),
        )
        val status = resolver.resolve(forecast, now = 1_780_000_000_500L)

        assertEquals(37.4220, status.locationLat, 0.0)
        assertEquals(-122.0841, status.locationLon, 0.0)
        assertEquals("NWS", status.source)
        assertEquals(66.79f, status.displayTempF!!, 0.001f)
        assertEquals(4.08f, status.appliedDeltaF!!, 0.001f)
        assertEquals(1.3f, status.deltaFromYesterdayF!!, 0.001f)
        assertEquals(1_780_000_000_000L, status.observedAtMs)
        assertEquals("Clear", status.condition)
        assertEquals(1_780_000_000_500L, status.updatedAt)
    }

    @Test
    fun `resolve passes nulls through when nothing is resolvable`() {
        val resolver = CurrentStatusResolver(
            latitude = 1.0,
            longitude = 2.0,
            source = "NWS",
            resolveTemp = { _, _ ->
                CurrentTempRun.Result(null, null, null, null, null, 0, 0)
            },
        )

        val status = resolver.resolve(
            ForecastSnapshot(
                sourceId = "NWS",
                raw = RawFetch(),
                resolved = ResolvedView(),
            ),
            now = 42L,
        )

        assertNull(status.displayTempF)
        assertNull(status.appliedDeltaF)
        assertNull(status.deltaFromYesterdayF)
        assertEquals(42L, status.updatedAt)
    }

    /** CURRENT_STATUS_PUBLISH: on change only, carrying the inputs (the 2026-10-13 lone-KSJC 73.4). */
    @Test
    fun `the publish log fires on a real change and stays quiet on a repeat`() {
        val logs = mutableListOf<String>()
        var next = CurrentTempRun.Result(
            displayTemp = 71.76f,
            appliedDelta = 1.5f,
            deltaFromYesterday = null,
            observedTemp = 71.5f,
            observedAt = 9L,
            windowObservations = 12,
            windowHourly = 4,
        )
        val resolver = CurrentStatusResolver(
            latitude = 1.0,
            longitude = 2.0,
            source = "NWS",
            logChange = { logs += it },
            resolveTemp = { _, _ -> next },
        )
        val snapshot = ForecastSnapshot(sourceId = "NWS", raw = RawFetch(), resolved = ResolvedView())

        resolver.resolve(snapshot, now = 1L)
        resolver.resolve(snapshot, now = 2L) // same value: quiet
        next = next.copy(displayTemp = 71.80f) // < 0.1°: quiet
        resolver.resolve(snapshot, now = 3L)
        next = next.copy(displayTemp = 73.4f, appliedDelta = null, observedTemp = 73.4f)
        resolver.resolve(snapshot, now = 4L)

        assertEquals(2, logs.size)
        assertEquals(
            "source=NWS display=71.76 appliedDelta=1.5 obs=71.5 observedAt=9 windowObs=12 windowHourly=4 " +
                "snapshotObs=0 snapshotNewestObs=null snapshotHourly=0 previous=null",
            logs[0],
        )
        assertEquals(true, logs[1].startsWith("source=NWS display=73.4 appliedDelta=null obs=73.4"))
        assertEquals(true, logs[1].endsWith("previous=71.76"))
    }
}
