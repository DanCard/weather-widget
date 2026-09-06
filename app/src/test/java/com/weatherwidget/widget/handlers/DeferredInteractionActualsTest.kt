package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The two-phase interaction paint (performance/260906-attribute-the-click-gap-and-extend-the-
 * deferred-actuals-fast-path.md, Tier 2): an opted-in tap paints the forecast curve first, skipping
 * the graph observation read, then repaints with actuals under the same interaction lock.
 *
 * Both decisions are pure functions precisely so they can be pinned here — the render itself needs
 * a Context, a database and a launcher, and none of those would make these rules any clearer.
 */
@Category(ShortDuration::class)
class DeferredInteractionActualsTest {

    // --- TemperatureStateResolver.shouldDeferGraphActuals -----------------------------------

    @Test
    fun `startup token still defers, so the startup fast path is unchanged`() {
        assertTrue(
            TemperatureStateResolver.shouldDeferGraphActuals(
                startupToken = "startup-123",
                deferGraphActualsRequested = false,
                useGraph = true,
            )
        )
    }

    @Test
    fun `an interaction can defer without a startup token`() {
        assertTrue(
            TemperatureStateResolver.shouldDeferGraphActuals(
                startupToken = null,
                deferGraphActualsRequested = true,
                useGraph = true,
            )
        )
    }

    @Test
    fun `nothing defers by default`() {
        assertFalse(
            TemperatureStateResolver.shouldDeferGraphActuals(
                startupToken = null,
                deferGraphActualsRequested = false,
                useGraph = true,
            )
        )
    }

    @Test
    fun `text mode never defers - it has no actual overlay to skip`() {
        assertFalse(
            "a startup token must not defer text mode",
            TemperatureStateResolver.shouldDeferGraphActuals(
                startupToken = "startup-123",
                deferGraphActualsRequested = false,
                useGraph = false,
            )
        )
        assertFalse(
            "an interaction must not defer text mode either",
            TemperatureStateResolver.shouldDeferGraphActuals(
                startupToken = null,
                deferGraphActualsRequested = true,
                useGraph = false,
            )
        )
    }

    // --- GraphInteractionRenderer.paintPlan --------------------------------------------------

    @Test
    fun `an opted-in temperature interaction paints twice, actuals-free first`() {
        val plan = GraphInteractionRenderer.paintPlan(
            deferActuals = true,
            viewMode = ViewMode.TEMPERATURE,
            requestPartialPush = false,
        )

        assertEquals(
            listOf(
                GraphInteractionRenderer.PaintPhase(deferGraphActuals = true, partialPush = false),
                GraphInteractionRenderer.PaintPhase(deferGraphActuals = false, partialPush = true),
            ),
            plan,
        )
    }

    @Test
    fun `phase 1 keeps the caller's delivery mode`() {
        // A partial-push caller (the worker's cache repaint shape) must not be silently upgraded to
        // a full update by opting into the fast path.
        val plan = GraphInteractionRenderer.paintPlan(
            deferActuals = true,
            viewMode = ViewMode.TEMPERATURE,
            requestPartialPush = true,
        )

        assertTrue(plan.all { it.partialPush })
    }

    @Test
    fun `precip and cloud paint once - they have no deferral to use`() {
        for (viewMode in listOf(ViewMode.PRECIPITATION, ViewMode.CLOUD_COVER)) {
            val plan = GraphInteractionRenderer.paintPlan(
                deferActuals = true,
                viewMode = viewMode,
                requestPartialPush = false,
            )

            assertEquals(
                "$viewMode must not paint twice",
                listOf(
                    GraphInteractionRenderer.PaintPhase(
                        deferGraphActuals = false,
                        partialPush = false,
                    )
                ),
                plan,
            )
        }
    }

    @Test
    fun `a caller that did not opt in paints once with full data`() {
        val plan = GraphInteractionRenderer.paintPlan(
            deferActuals = false,
            viewMode = ViewMode.TEMPERATURE,
            requestPartialPush = false,
        )

        assertEquals(
            listOf(
                GraphInteractionRenderer.PaintPhase(deferGraphActuals = false, partialPush = false)
            ),
            plan,
        )
    }
}
