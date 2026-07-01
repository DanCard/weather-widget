package com.weatherwidget.widget

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pure-function coverage for [ProcessExitLogger]'s formatting and severity mapping. The
 * device-dependent read ([ProcessExitLogger.logRecentExitsOnce]) is exercised on-device; here we lock
 * in the reason→name/level mapping and the one-line format so a future refactor can't silently turn a
 * LOW_MEMORY reap into an unclassified/mislabelled row. [ApplicationExitInfo] has no public
 * constructor, so we test the primitive overload directly (see the codebase's pure-function testing
 * strategy).
 */
@Category(ShortDuration::class)
class ProcessExitLoggerTest {
    @Test
    fun reasonName_mapsKnownReasons() {
        assertEquals("LOW_MEMORY", ProcessExitLogger.reasonName(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("CRASH", ProcessExitLogger.reasonName(ApplicationExitInfo.REASON_CRASH))
        assertEquals("CRASH_NATIVE", ProcessExitLogger.reasonName(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("ANR", ProcessExitLogger.reasonName(ApplicationExitInfo.REASON_ANR))
        assertEquals("USER_REQUESTED", ProcessExitLogger.reasonName(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("SIGNALED", ProcessExitLogger.reasonName(ApplicationExitInfo.REASON_SIGNALED))
    }

    @Test
    fun reasonName_unknownReasonIsLabelledNotDropped() {
        assertEquals("UNKNOWN(9999)", ProcessExitLogger.reasonName(9999))
    }

    @Test
    fun levelForReason_crashesAreError_reapsAndForceStopsAreWarn() {
        assertEquals("ERROR", ProcessExitLogger.levelForReason(ApplicationExitInfo.REASON_CRASH))
        assertEquals("ERROR", ProcessExitLogger.levelForReason(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("WARN", ProcessExitLogger.levelForReason(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("WARN", ProcessExitLogger.levelForReason(ApplicationExitInfo.REASON_ANR))
        assertEquals("WARN", ProcessExitLogger.levelForReason(ApplicationExitInfo.REASON_USER_REQUESTED))
        // Benign/expected exits stay INFO so a DB query can filter them out.
        assertEquals("INFO", ProcessExitLogger.levelForReason(ApplicationExitInfo.REASON_EXIT_SELF))
        assertEquals("INFO", ProcessExitLogger.levelForReason(ApplicationExitInfo.REASON_SIGNALED))
    }

    @Test
    fun formatExit_isOneLineWithKeyFieldsForQuerying() {
        val line =
            ProcessExitLogger.formatExit(
                reason = ApplicationExitInfo.REASON_LOW_MEMORY,
                timestampMs = 0L,
                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
                status = 0,
                pssKb = 123L,
                rssKb = 456L,
                processName = "com.weatherwidget",
                description = "isExcessiveCpu=false",
            )
        assertTrue(line, line.contains("reason=LOW_MEMORY"))
        assertTrue(line, line.contains("importance=CACHED"))
        assertTrue(line, line.contains("pss=123KB"))
        assertTrue(line, line.contains("rss=456KB"))
        assertTrue(line, line.contains("process=com.weatherwidget"))
        assertTrue(line, line.contains("desc=\"isExcessiveCpu=false\""))
        assertTrue("must be a single line", !line.contains("\n"))
    }

    @Test
    fun formatExit_nullDescriptionRendersEmpty() {
        val line =
            ProcessExitLogger.formatExit(
                reason = ApplicationExitInfo.REASON_SIGNALED,
                timestampMs = 0L,
                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE,
                status = 9,
                pssKb = 0L,
                rssKb = 0L,
                processName = null,
                description = null,
            )
        assertTrue(line, line.contains("desc=\"\""))
        assertTrue(line, line.contains("status=9"))
    }
}
