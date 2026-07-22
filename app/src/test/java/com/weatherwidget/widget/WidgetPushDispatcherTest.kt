package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Covers the pure decisions behind the WIDGET_PUSH breadcrumb. The push itself needs an
 * AppWidgetManager, so only the decisions are unit-tested here — that is the seam that matters,
 * since the breadcrumb is the whole point of the class.
 */
@Category(ShortDuration::class)
class WidgetPushDispatcherTest {

    @Test
    fun `partial with no full push this process is unbacked`() {
        assertTrue(WidgetPushDispatcher.isUnbackedPartial(partialPush = true, hasFullPushedThisProcess = false))
    }

    @Test
    fun `partial after a full push this process is backed`() {
        assertFalse(WidgetPushDispatcher.isUnbackedPartial(partialPush = true, hasFullPushedThisProcess = true))
    }

    @Test
    fun `full push is never unbacked - it establishes the cache itself`() {
        assertFalse(WidgetPushDispatcher.isUnbackedPartial(partialPush = false, hasFullPushedThisProcess = false))
        assertFalse(WidgetPushDispatcher.isUnbackedPartial(partialPush = false, hasFullPushedThisProcess = true))
    }

    @Test
    fun `steady-state partials are not persisted so app_logs is not swamped`() {
        assertFalse(WidgetPushDispatcher.shouldPersist(isFirstPushForWidget = false, isFullPush = false))
    }

    @Test
    fun `first push per widget is persisted`() {
        assertTrue(WidgetPushDispatcher.shouldPersist(isFirstPushForWidget = true, isFullPush = false))
    }

    @Test
    fun `every full push is persisted not just the first`() {
        // A full push replaces the whole view tree, so it is the transition that can strand a
        // widget on the bare widget_weather layout. Logging only the first per process left the
        // 2026-07-22 investigation with no row for the push that mattered.
        assertTrue(WidgetPushDispatcher.shouldPersist(isFirstPushForWidget = false, isFullPush = true))
    }

    @Test
    fun `complete-body unbacked partial is promoted to full`() {
        assertTrue(
            WidgetPushDispatcher.shouldPromoteToFull(
                partialPush = true, bodyComplete = true, hasFullPushedThisProcess = false,
            ),
        )
    }

    @Test
    fun `header-only unbacked partial is not promoted - promoting would blank the body`() {
        assertFalse(
            WidgetPushDispatcher.shouldPromoteToFull(
                partialPush = true, bodyComplete = false, hasFullPushedThisProcess = false,
            ),
        )
    }

    @Test
    fun `backed partial is not promoted - the full push behind it makes partial safe`() {
        assertFalse(
            WidgetPushDispatcher.shouldPromoteToFull(
                partialPush = true, bodyComplete = true, hasFullPushedThisProcess = true,
            ),
        )
    }

    @Test
    fun `full push is never promoted - it is already full`() {
        assertFalse(
            WidgetPushDispatcher.shouldPromoteToFull(
                partialPush = false, bodyComplete = true, hasFullPushedThisProcess = false,
            ),
        )
    }

    @Test
    fun `effectivePartialPush returns false when complete-body partial is unbacked`() {
        assertFalse(
            WidgetPushDispatcher.effectivePartialPush(
                requestedPartialPush = true, bodyComplete = true, hasFullPushedThisProcess = false,
            ),
        )
        assertTrue(
            WidgetPushDispatcher.effectivePartialPush(
                requestedPartialPush = true, bodyComplete = true, hasFullPushedThisProcess = true,
            ),
        )
    }

    @Test
    fun `message carries origin, requested push, effective push, pid and unbacked verdict`() {
        val message = WidgetPushDispatcher.pushLogMessage(
            appWidgetId = 345,
            caller = "TEMPERATURE",
            origin = WidgetPushDispatcher.Origin.UI_ONLY,
            requestedPartialPush = true,
            effectivePartialPush = true,
            hasFullPushedThisProcess = false,
            pid = 31891,
        )
        assertEquals(
            "widget=345 caller=TEMPERATURE origin=UI_ONLY requestedPush=partial push=partial pid=31891 fullThisProcess=false unbackedPartial=true",
            message,
        )
    }

    @Test
    fun `full push message reports full and a backed verdict`() {
        val message = WidgetPushDispatcher.pushLogMessage(
            appWidgetId = 345,
            caller = "DAILY",
            origin = WidgetPushDispatcher.Origin.WORKER_FETCH,
            requestedPartialPush = false,
            effectivePartialPush = false,
            hasFullPushedThisProcess = true,
            pid = 100,
        )
        assertEquals(
            "widget=345 caller=DAILY origin=WORKER_FETCH requestedPush=full push=full pid=100 fullThisProcess=true unbackedPartial=false",
            message,
        )
    }

    @Test
    fun `direct loading and error fallbacks report their origins and full mode`() {
        val loadingMsg = WidgetPushDispatcher.pushLogMessage(
            appWidgetId = 50,
            caller = "LOADING",
            origin = WidgetPushDispatcher.Origin.LOADING,
            requestedPartialPush = false,
            effectivePartialPush = false,
            hasFullPushedThisProcess = false,
            pid = 100,
        )
        assertEquals(
            "widget=50 caller=LOADING origin=LOADING requestedPush=full push=full pid=100 fullThisProcess=false unbackedPartial=false",
            loadingMsg,
        )

        val errorMsg = WidgetPushDispatcher.pushLogMessage(
            appWidgetId = 50,
            caller = "ERROR",
            origin = WidgetPushDispatcher.Origin.ERROR,
            requestedPartialPush = false,
            effectivePartialPush = false,
            hasFullPushedThisProcess = false,
            pid = 100,
        )
        assertEquals(
            "widget=50 caller=ERROR origin=ERROR requestedPush=full push=full pid=100 fullThisProcess=false unbackedPartial=false",
            errorMsg,
        )
    }

    @Test
    fun `healthy startup sequence delivers single full then subsequent refresh stays partial`() {
        var backed = false

        // 1. Initial unbacked request: promoted to full push (effectivePartial = false)
        val initialEffectivePartial = WidgetPushDispatcher.effectivePartialPush(
            requestedPartialPush = true,
            bodyComplete = true,
            hasFullPushedThisProcess = backed,
        )
        assertFalse(initialEffectivePartial)

        // Once the full push succeeds, process marks widget as backed:
        backed = true

        // 2. Subsequent follow-up refresh requesting partial: remains partial (effectivePartial = true)
        val followUpEffectivePartial = WidgetPushDispatcher.effectivePartialPush(
            requestedPartialPush = true,
            bodyComplete = true,
            hasFullPushedThisProcess = backed,
        )
        assertTrue(followUpEffectivePartial)
    }

    @Test
    fun `unbacked or failed first paint still permits recovery full on follow-up`() {
        // If initial paint failed or did not occur, process remains unbacked (hasFullPushedThisProcess = false)
        val backed = false

        val followUpEffectivePartial = WidgetPushDispatcher.effectivePartialPush(
            requestedPartialPush = true,
            bodyComplete = true,
            hasFullPushedThisProcess = backed,
        )
        // Follow-up is promoted to full (effectivePartial = false) as self-heal
        assertFalse(followUpEffectivePartial)
    }
}
