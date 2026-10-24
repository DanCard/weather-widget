package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder

/** "Host gone" outlives a restart: it was in memory, so every daily restart asked the gone host again. */
@Category(ShortDuration::class)
class DesktopWeatherNextGoneStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a mark survives a new instance and clear removes it`() {
        val file = tmp.root.toPath().resolve("weathernext_gone")
        DesktopWeatherNextGoneStore(file).markGone(410, nowMs = 1_234L)

        val afterRestart = DesktopWeatherNextGoneStore(file)
        assertEquals(1_234L, afterRestart.goneAtMs)
        assertEquals(410, afterRestart.goneStatus)

        afterRestart.clear()
        assertNull(DesktopWeatherNextGoneStore(file).goneAtMs)
    }

    @Test
    fun `a missing or garbled file reads as not gone`() {
        val file = tmp.root.toPath().resolve("weathernext_gone")
        assertNull(DesktopWeatherNextGoneStore(file).goneAtMs)
        file.toFile().writeText("garbage")
        assertNull(DesktopWeatherNextGoneStore(file).goneAtMs)
        assertEquals(404, DesktopWeatherNextGoneStore(file).goneStatus)
    }
}
