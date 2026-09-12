package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Regression for the blank temperature widget of 2026-09-12 (widget #88, four minutes).
 *
 * `TemperatureGraphRenderer.renderGraph` opens with `job?.ensureActive()`. The resolver wrapped the
 * call in a bare `catch (e: Exception)`, which also catches `CancellationException`, so a paint
 * cancelled by its replacement carried on inside a dead coroutine, produced a null bitmap and
 * pushed the un-populated text layout — an empty body. See
 * plans/260912-cancelled-temperature-render-pushes-blank-body.md.
 *
 * [TemperatureStateResolver.renderOrNull] is the extracted guard: cancellation must propagate,
 * every other failure becomes null and is reported.
 */
@Category(ShortDuration::class)
class TemperatureStateResolverRenderGuardTest {

    @Test
    fun `cancellation propagates instead of becoming a null bitmap`() {
        val cancellation = CancellationException("Job was cancelled")
        var reported: Exception? = null

        try {
            TemperatureStateResolver.renderOrNull<Any>(onFailure = { reported = it }) { throw cancellation }
            fail("a cancelled render must not return")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
        assertNull("cancellation is not a failure and must not be reported as one", reported)
    }

    @Test
    fun `a real render failure becomes null and is reported once`() {
        val failure = IllegalStateException("renderGraph exploded")
        val reported = mutableListOf<Exception>()

        val result = TemperatureStateResolver.renderOrNull<Any>(onFailure = { reported.add(it) }) { throw failure }

        assertNull(result)
        assertEquals(listOf<Exception>(failure), reported)
    }

    @Test
    fun `a successful render passes its result through untouched`() {
        var reported = false

        val result = TemperatureStateResolver.renderOrNull(onFailure = { reported = true }) { "bitmap" }

        assertEquals("bitmap", result)
        assertTrue(!reported)
    }
}
