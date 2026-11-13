package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * The daemon's WatchService reports only changes made after it is registered, which can be seconds
 * after the process starts. [pendingStartupTriggers] finds the trigger files written in that window
 * so a panel click or a refresh request made during startup is not lost.
 */
@Category(ShortDuration::class)
class PendingStartupTriggersTest {
    private val processStartMs = 1_000_000L

    private fun dirWith(vararg files: Pair<String, Long>): Path {
        val dir = Files.createTempDirectory("pending-triggers")
        files.forEach { (name, modifiedMs) ->
            val file = Files.writeString(dir.resolve(name), "")
            Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedMs))
        }
        return dir
    }

    @Test
    fun `a trigger written at or after process start is pending`() {
        val dir = dirWith(SHOW_TRIGGER to processStartMs, REFRESH_REQUESTED_TRIGGER to processStartMs + 4_000)
        assertEquals(listOf(REFRESH_REQUESTED_TRIGGER, SHOW_TRIGGER), pendingStartupTriggers(dir, processStartMs))
    }

    @Test
    fun `a trigger left over from before process start is not pending`() {
        val dir = dirWith(SHOW_TRIGGER to processStartMs - 1, REFRESH_REQUESTED_TRIGGER to processStartMs - 60_000)
        assertEquals(emptyList<String>(), pendingStartupTriggers(dir, processStartMs))
    }

    @Test
    fun `config is handled before refresh and refresh before show`() {
        val dir = dirWith(
            SHOW_TRIGGER to processStartMs + 1,
            CONFIG_CHANGED_TRIGGER to processStartMs + 3,
            REFRESH_REQUESTED_TRIGGER to processStartMs + 2,
        )
        assertEquals(
            listOf(CONFIG_CHANGED_TRIGGER, REFRESH_REQUESTED_TRIGGER, SHOW_TRIGGER),
            pendingStartupTriggers(dir, processStartMs),
        )
    }

    @Test
    fun `only the triggers the daemon consumes are pending`() {
        // The daemon writes .data-updated and .ui-show itself; quit signals have their own re-check.
        val dir = dirWith(
            DATA_UPDATED_TRIGGER to processStartMs + 1,
            UI_SHOW_TRIGGER to processStartMs + 1,
            QUIT_TRIGGER to processStartMs + 1,
        )
        assertEquals(emptyList<String>(), pendingStartupTriggers(dir, processStartMs))
        assertEquals(emptyList<String>(), pendingStartupTriggers(dir.resolve("missing"), processStartMs))
    }
}
