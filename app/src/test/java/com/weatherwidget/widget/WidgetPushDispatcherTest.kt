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
    fun `steady-state pushes are not persisted so app_logs is not swamped`() {
        assertFalse(WidgetPushDispatcher.shouldPersist(isFirstPushForWidget = false, isFirstFullPushForWidget = false))
    }

    @Test
    fun `first push and first full push per widget are persisted`() {
        assertTrue(WidgetPushDispatcher.shouldPersist(isFirstPushForWidget = true, isFirstFullPushForWidget = false))
        assertTrue(WidgetPushDispatcher.shouldPersist(isFirstPushForWidget = false, isFirstFullPushForWidget = true))
    }

    @Test
    fun `message carries pid and the unbacked verdict that 2026-07-14 lacked`() {
        val message = WidgetPushDispatcher.pushLogMessage(
            appWidgetId = 345,
            caller = "TEMPERATURE",
            partialPush = true,
            hasFullPushedThisProcess = false,
            pid = 31891,
        )
        assertEquals(
            "widget=345 caller=TEMPERATURE push=partial pid=31891 fullThisProcess=false unbackedPartial=true",
            message,
        )
    }

    @Test
    fun `full push message reports full and a backed verdict`() {
        val message = WidgetPushDispatcher.pushLogMessage(
            appWidgetId = 345,
            caller = "DAILY",
            partialPush = false,
            hasFullPushedThisProcess = true,
            pid = 100,
        )
        assertEquals(
            "widget=345 caller=DAILY push=full pid=100 fullThisProcess=true unbackedPartial=false",
            message,
        )
    }
}
