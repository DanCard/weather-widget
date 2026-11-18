package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class SourceListEditTest {
    private val before = listOf("NWS", "SILURIAN", "GOOGLE_WEATHER", "OPEN_METEO")

    @Test
    fun `identical lists are no edit`() {
        assertNull(SourceListEdit.classify(before, before.toList()))
    }

    @Test
    fun `the 2026-11-18 drag is a pure reorder`() {
        val after = listOf("NWS", "SILURIAN", "OPEN_METEO", "GOOGLE_WEATHER")
        val change = SourceListEdit.classify(before, after)!!
        println("reorder: $change")
        assertEquals("reorder", change.kind)
        assertTrue(change.added.isEmpty() && change.removed.isEmpty())
    }

    @Test
    fun `an add or a remove alone does not count as a reorder`() {
        val added = SourceListEdit.classify(before, before + "TOMORROW_IO")!!
        assertEquals("add", added.kind)
        assertEquals(listOf("TOMORROW_IO"), added.added)

        val removed = SourceListEdit.classify(before, before - "SILURIAN")!!
        assertEquals("remove", removed.kind)
        assertEquals(listOf("SILURIAN"), removed.removed)
    }

    @Test
    fun `one save can add, remove and reorder`() {
        val change = SourceListEdit.classify(before, listOf("OPEN_METEO", "NWS", "TOMORROW_IO"))!!
        assertEquals("add+remove+reorder", change.kind)
    }

    @Test
    fun `an edit fetches only when the shown source has nothing to draw`() {
        assertFalse(SourceListEdit.fetchesForecast(cachePresent = true))
        assertTrue(SourceListEdit.fetchesForecast(cachePresent = false))
    }

    @Test
    fun `log line names platform, kind and the new order`() {
        val after = before - "SILURIAN"
        val msg = SourceListEdit.logMessage("desktop", SourceListEdit.classify(before, after)!!, after)
        println(msg)
        assertEquals(
            "platform=desktop kind=remove added=[] removed=[SILURIAN] order=[NWS, GOOGLE_WEATHER, OPEN_METEO]",
            msg,
        )
    }
}
