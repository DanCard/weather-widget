package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder

@Category(ShortDuration::class)
class DesktopSynopticBackoffStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `state written by one process is read by another`() {
        val file = tmp.root.toPath().resolve("synoptic_fetch_backoff")
        DesktopSynopticBackoffStore(file).save(failStreak = 2, backoffUntilMs = 123_456L)

        val other = DesktopSynopticBackoffStore(file)
        assertEquals(2, other.failStreak())
        assertEquals(123_456L, other.backoffUntilMs())
    }

    @Test
    fun `a missing or corrupt file reads as no backoff`() {
        val file = tmp.root.toPath().resolve("synoptic_fetch_backoff")
        assertEquals(0L, DesktopSynopticBackoffStore(file).backoffUntilMs())
        java.nio.file.Files.writeString(file, "garbage")
        assertEquals(0, DesktopSynopticBackoffStore(file).failStreak())
        assertEquals(0L, DesktopSynopticBackoffStore(file).backoffUntilMs())
    }
}
