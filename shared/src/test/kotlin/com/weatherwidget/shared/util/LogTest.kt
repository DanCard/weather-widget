package com.weatherwidget.shared.util

import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.shared.graph.TemperatureLabelResolver
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class LogTest {

    private data class Entry(
        val priority: Log.Priority,
        val tag: String,
        val msg: String,
        val tr: Throwable?,
    )

    private class CapturingSink : Log.Sink {
        val entries = mutableListOf<Entry>()
        override fun log(priority: Log.Priority, tag: String, msg: String, tr: Throwable?) {
            entries.add(Entry(priority, tag, msg, tr))
        }
    }

    @After
    fun tearDown() {
        // Never leak the test sink into other tests in the suite.
        Log.resetToDefault()
    }

    @Test
    fun `installed sink receives each level with correct priority and tag`() {
        val sink = CapturingSink()
        Log.install(sink)

        Log.v("TagV", "verbose-msg")
        Log.d("TagD", "debug-msg")
        Log.i("TagI", "info-msg")
        Log.w("TagW", "warn-msg")
        Log.e("TagE", "error-msg")

        assertEquals(5, sink.entries.size)
        assertEquals(Entry(Log.Priority.VERBOSE, "TagV", "verbose-msg", null), sink.entries[0])
        assertEquals(Entry(Log.Priority.DEBUG, "TagD", "debug-msg", null), sink.entries[1])
        assertEquals(Entry(Log.Priority.INFO, "TagI", "info-msg", null), sink.entries[2])
        assertEquals(Entry(Log.Priority.WARN, "TagW", "warn-msg", null), sink.entries[3])
        assertEquals(Entry(Log.Priority.ERROR, "TagE", "error-msg", null), sink.entries[4])
    }

    @Test
    fun `throwable overloads pass the throwable through to the sink`() {
        val sink = CapturingSink()
        Log.install(sink)
        val boom = IllegalStateException("boom")

        Log.w("TagW", "warn-with-tr", boom)
        Log.e("TagE", "error-with-tr", boom)
        Log.d("TagD", "debug-no-tr")

        assertEquals(3, sink.entries.size)
        assertSame(boom, sink.entries[0].tr)
        assertSame(boom, sink.entries[1].tr)
        assertNull("non-throwable overload must pass null", sink.entries[2].tr)
    }

    @Test
    fun `resetToDefault detaches the installed sink`() {
        val sink = CapturingSink()
        Log.install(sink)
        Log.d("Tag", "before-reset")
        assertEquals(1, sink.entries.size)

        Log.resetToDefault()
        Log.d("Tag", "after-reset")

        assertEquals("reset must stop delivering to the old sink", 1, sink.entries.size)
    }

    // Regression guard for the recurring "label silently missing on-device" class of bug: the
    // resolver's placement breadcrumbs (LabelAccepted / LabelSuppressed) must actually reach the
    // installed sink. They were historically dropped because the default sink routes to
    // java.util.logging, which is invisible in Android logcat. Reproduces the flat-curve shape seen
    // on the Samsung widget (a ~2-3 degree range over 21 hours).
    @Test
    fun `resolver placement breadcrumbs reach the installed sink`() {
        val sink = CapturingSink()
        Log.install(sink)

        val start = LocalDateTime.of(2026, 6, 11, 2, 0)
        val hours = (0 until 21).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = if (offset == 11) 63.0f else 64.0f,
                label = "${dt.hour}h",
                isActual = offset <= 17,
                actualTemperature = if (offset == 17) 62.6f else 64.0f,
            )
        }

        val extrema = TemperatureLabelResolver.computeExtremaIndices(hours, null, 17, null, useCelsius = false)
        TemperatureLabelResolver.collectLabelCandidates(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = 17,
            transitionX = null,
            observedAt = null, useCelsius = false,
        )

        val resolverLines = sink.entries.filter { it.tag == "TempLabelResolver" }
        assertTrue("resolver should emit diagnostics through the shared Log sink", resolverLines.isNotEmpty())
        assertTrue(
            "resolver should log per-candidate accept/suppress decisions reachable in logcat",
            resolverLines.any { it.msg.startsWith("LabelAccepted") || it.msg.startsWith("LabelSuppressed") },
        )
    }
}
