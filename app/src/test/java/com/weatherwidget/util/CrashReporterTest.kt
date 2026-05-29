package com.weatherwidget.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class CrashReporterTest {
    @Test
    fun `formatCrashMessage includes thread name and exception type`() {
        val message = CrashReporter.formatCrashMessage("main", IllegalStateException("boom"))

        assertTrue("should include thread name", message.contains("thread=main"))
        assertTrue("should include exception class", message.contains("IllegalStateException"))
        assertTrue("should include exception message", message.contains("boom"))
    }

    @Test
    fun `formatCrashMessage includes the cause chain`() {
        val cause = IllegalArgumentException("root cause")
        val throwable = RuntimeException("wrapper", cause)

        val message = CrashReporter.formatCrashMessage("worker-1", throwable)

        assertTrue("should include wrapper", message.contains("wrapper"))
        // stackTraceToString() renders nested causes with a "Caused by:" prefix.
        assertTrue("should include cause", message.contains("Caused by"))
        assertTrue("should include root cause message", message.contains("root cause"))
    }

    @Test
    fun `formatCrashMessage starts with the thread line`() {
        val message = CrashReporter.formatCrashMessage("ui", RuntimeException("x"))

        assertTrue("thread line should be first", message.startsWith("thread=ui\n"))
    }
}
