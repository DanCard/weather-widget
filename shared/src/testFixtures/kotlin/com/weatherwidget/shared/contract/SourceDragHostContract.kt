package com.weatherwidget.shared.contract

import com.weatherwidget.shared.sourceview.SourceDragRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a [SourceDragRun.Host] means on each platform: the drop reaches the platform's save, and both
 * `SOURCE_DRAG` lines reach its real `app_logs`, so one query answers what a drag did on either.
 */
abstract class SourceDragHostContract {
    /** The platform's host, against its real log store, saving through [onSave]. */
    protected abstract fun host(onSave: (List<String>) -> Unit): SourceDragRun.Host

    /** The [tag] rows in `app_logs`, once at least [count] are written (hosts may write off-thread). */
    protected abstract fun storedLogs(tag: String, count: Int): List<String>

    private val before = listOf("NWS", "TOMORROW_IO")
    private val after = listOf("TOMORROW_IO", "NWS")

    @Test
    fun `a moved drop saves and stores both lines`() {
        val saves = mutableListOf<List<String>>()
        val run = SourceDragRun("contract", host { saves += it })

        run.start("TOMORROW_IO", before)
        run.finish(after)

        assertEquals(listOf(after), saves)
        val rows = storedLogs(SourceDragRun.TAG, 2)
        assertEquals(rows.toString(), 2, rows.size)
        assertTrue(rows.toString(), rows.any { "phase=start id=TOMORROW_IO" in it })
        assertTrue(rows.toString(), rows.any { "phase=end id=TOMORROW_IO outcome=moved" in it })
    }

    @Test
    fun `an unchanged drop stores its end and saves nothing`() {
        val saves = mutableListOf<List<String>>()
        val run = SourceDragRun("contract", host { saves += it })

        run.start("NWS", before)
        run.finish(before)

        assertTrue(saves.isEmpty())
        assertTrue(storedLogs(SourceDragRun.TAG, 2).any { "outcome=unchanged" in it })
    }
}
