package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Regression cover for the stale-source paint that clobbered a correct cloud graph on the Fold
 * (2026-09-01, widget 345).
 *
 * The worker held 467 hourly rows scoped to SILURIAN+NWS while the user had just selected
 * TOMORROW_IO. `sourceFilteredHourly` went to zero, the handler drew "Cloud data unavailable", and
 * that frame was pushed 265ms AFTER the user's own correct render — then sat on screen for 40
 * minutes, because nothing repaints an idle widget.
 *
 * `sourceMissingFromLoad` already flagged the condition; it just wasn't allowed to stop the paint.
 *
 * See plans/260901-stale-source-paint-clobbers-hourly-graph.md.
 */
@Category(ShortDuration::class)
class WidgetRendererStaleSourcePaintTest {

    @Test
    fun `background repaint with no rows for the display source is skipped`() {
        // The observed failure, exactly: WORKER_FETCH paint, CLOUD_COVER on screen, zero rows for
        // TOMORROW_IO, widget already showing a real graph.
        assertTrue(
            "A worker repaint that provably cannot draw the source must not overwrite the screen",
            WidgetRenderer.shouldSkipStaleSourcePaint(
                sourceMissingFromLoad = true,
                viewMode = ViewMode.CLOUD_COVER,
                origin = WidgetPushDispatcher.Origin.WORKER_FETCH,
                hasPaintedBody = true,
            ),
        )
    }

    @Test
    fun `every background origin is skippable`() {
        // A toggle can land during any of these; none of them is the user asking to see this frame.
        val backgroundOrigins = listOf(
            WidgetPushDispatcher.Origin.WORKER_FETCH,
            WidgetPushDispatcher.Origin.WORKER_CACHE,
            WidgetPushDispatcher.Origin.UI_ONLY,
        )
        backgroundOrigins.forEach { origin ->
            assertTrue(
                "origin=$origin is a background repaint and must be skippable",
                WidgetRenderer.shouldSkipStaleSourcePaint(
                    sourceMissingFromLoad = true,
                    viewMode = ViewMode.TEMPERATURE,
                    origin = origin,
                    hasPaintedBody = true,
                ),
            )
        }
    }

    @Test
    fun `user-driven repaints still paint the genuine gap`() {
        // A USER_INTERACTION paint re-reads rows for the source the user just picked, so a miss
        // there means the API really has nothing. Skipping would swap an honest "no data" message
        // for a silently stale graph — strictly worse than the bug being fixed.
        val interactiveOrigins = listOf(
            WidgetPushDispatcher.Origin.USER_INTERACTION,
            WidgetPushDispatcher.Origin.ACTION_REFRESH,
            WidgetPushDispatcher.Origin.PROVIDER_ON_UPDATE,
            WidgetPushDispatcher.Origin.RESIZE,
        )
        interactiveOrigins.forEach { origin ->
            assertFalse(
                "origin=$origin is user-driven; a real gap must reach the screen",
                WidgetRenderer.shouldSkipStaleSourcePaint(
                    sourceMissingFromLoad = true,
                    viewMode = ViewMode.CLOUD_COVER,
                    origin = origin,
                    hasPaintedBody = true,
                ),
            )
        }
    }

    @Test
    fun `daily view never skips`() {
        // DAILY renders from the unified list and the forecast rows, never from sourceFilteredHourly,
        // so the flag says nothing about what it would draw.
        assertFalse(
            "DAILY does not consume sourceFilteredHourly, so the flag must not gate it",
            WidgetRenderer.shouldSkipStaleSourcePaint(
                sourceMissingFromLoad = true,
                viewMode = ViewMode.DAILY,
                origin = WidgetPushDispatcher.Origin.WORKER_FETCH,
                hasPaintedBody = true,
            ),
        )
    }

    @Test
    fun `a widget with no painted body never skips`() {
        // Fresh process / force-stop / app update: the widget may still show "Loading…". Skipping
        // strands it there with no later trigger — the same trap shouldSkipDailyUiOnlyRepaint guards.
        assertFalse(
            "An unpainted widget must fall through rather than be stranded on the placeholder",
            WidgetRenderer.shouldSkipStaleSourcePaint(
                sourceMissingFromLoad = true,
                viewMode = ViewMode.CLOUD_COVER,
                origin = WidgetPushDispatcher.Origin.WORKER_FETCH,
                hasPaintedBody = false,
            ),
        )
    }

    @Test
    fun `a covered source paints normally`() {
        // The overwhelmingly common path: nothing missing, nothing to skip. Pairs with the positive
        // case above so the predicate can fail in both directions.
        assertFalse(
            "A normal repaint must not be skipped",
            WidgetRenderer.shouldSkipStaleSourcePaint(
                sourceMissingFromLoad = false,
                viewMode = ViewMode.CLOUD_COVER,
                origin = WidgetPushDispatcher.Origin.WORKER_FETCH,
                hasPaintedBody = true,
            ),
        )
    }
}
