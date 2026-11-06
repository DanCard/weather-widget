package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.MediumDuration
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

@OptIn(ExperimentalCoroutinesApi::class)
@Category(MediumDuration::class)
class WidgetPreemptionSettlerTest : RobolectricTest() {

    private lateinit var context: Context
    private val repository: WeatherRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WidgetPreemptionSettler.clearForTesting()
    }

    @After
    fun tearDown() {
        WidgetPreemptionSettler.clearForTesting()
    }

    @Test
    fun `recordPreempted debounces and settles widgets from cache`() = runTest {
        val renderedList = mutableListOf<Int>()
        WidgetPreemptionSettler.renderWidget = { _, id, _ -> renderedList.add(id) }
        WidgetPreemptionSettler.scope = this
        WidgetPreemptionSettler.settlementDelayMs = 1000L

        WidgetPreemptionSettler.recordPreempted(context, listOf(101, 102), repository)

        assertEquals(setOf(101, 102), WidgetPreemptionSettler.pendingWidgets())
        assertTrue(WidgetStateManager(context).isPaintOwed())

        // Advance 500ms (not yet settled)
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertTrue("No renders should have occurred yet", renderedList.isEmpty())

        // Second preemption at 500ms extends debounce by 1000ms (settlement target is t = 1500ms)
        WidgetPreemptionSettler.recordPreempted(context, listOf(103), repository)
        assertEquals(setOf(101, 102, 103), WidgetPreemptionSettler.pendingWidgets())

        // Advance to t = 1100ms (only 600ms since second preemption; target is 1500ms)
        testScheduler.advanceTimeBy(600)
        testScheduler.runCurrent()
        assertTrue("No renders should have occurred before debounce expires", renderedList.isEmpty())

        // Advance remaining 500ms to t = 1600ms (exceeds 1500ms target -> fires settlement)
        testScheduler.advanceTimeBy(500)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(101, 102, 103), renderedList)
        assertTrue(WidgetPreemptionSettler.pendingWidgets().isEmpty())
    }

    @Test
    fun `a tap that preempted nothing still holds back a pending settlement`() = runTest {
        val renderedList = mutableListOf<Int>()
        WidgetPreemptionSettler.renderWidget = { _, id, _ -> renderedList.add(id) }
        WidgetPreemptionSettler.scope = this
        WidgetPreemptionSettler.settlementDelayMs = 1000L

        WidgetPreemptionSettler.recordPreempted(context, listOf(101), repository)
        testScheduler.advanceTimeBy(900)
        WidgetPreemptionSettler.extendIfPending(context, repository)
        testScheduler.advanceTimeBy(900)
        testScheduler.runCurrent()
        assertTrue("The tap at 900 ms moved settlement to 1900 ms", renderedList.isEmpty())

        testScheduler.advanceUntilIdle()
        assertEquals(listOf(101), renderedList)
    }

    @Test
    fun `extending with nothing pending schedules nothing`() = runTest {
        val renderedList = mutableListOf<Int>()
        WidgetPreemptionSettler.renderWidget = { _, id, _ -> renderedList.add(id) }
        WidgetPreemptionSettler.scope = this

        WidgetPreemptionSettler.extendIfPending(context, repository)

        testScheduler.advanceUntilIdle()
        assertTrue(renderedList.isEmpty())
    }

    /**
     * Pixel 7 Pro 2026-11-06: a startup follow-up deferred by the repaint gate was recorded with a
     * null repository, and its settle painted widget 88 with no observations (rawObs=0) — no actual
     * line, no labels — until the next worker paint. A settle must never render without one.
     */
    @Test
    fun `settle resolves a repository when the preempting caller had none`() = runTest {
        val resolved: WeatherRepository = mockk(relaxed = true)
        val renderedWith = mutableListOf<WeatherRepository?>()
        WidgetPreemptionSettler.renderWidget = { _, _, repo -> renderedWith.add(repo) }
        WidgetPreemptionSettler.resolveRepository = { resolved }
        WidgetPreemptionSettler.scope = this
        WidgetPreemptionSettler.settlementDelayMs = 1000L

        WidgetPreemptionSettler.recordPreempted(context, listOf(88), null)
        testScheduler.advanceUntilIdle()

        println("settle with null caller repository rendered with: $renderedWith")
        assertEquals(1, renderedWith.size)
        assertTrue("settle rendered without a repository", renderedWith.single() === resolved)
    }

    @Test
    fun `settle keeps the repository its caller passed`() = runTest {
        val renderedWith = mutableListOf<WeatherRepository?>()
        WidgetPreemptionSettler.renderWidget = { _, _, repo -> renderedWith.add(repo) }
        WidgetPreemptionSettler.resolveRepository = { error("must not resolve when one was passed") }
        WidgetPreemptionSettler.scope = this
        WidgetPreemptionSettler.settlementDelayMs = 1000L

        WidgetPreemptionSettler.recordPreempted(context, listOf(88), repository)
        testScheduler.advanceUntilIdle()

        assertTrue(renderedWith.single() === repository)
    }
}
