package com.weatherwidget.desktop

import com.weatherwidget.shared.notify.DominantTempWatchState
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * The watch state deliberately lives in its own file rather than in `DesktopConfig` — see
 * [DominantTempWatchStore]'s kdoc for why. These tests pin the two properties that file has to have:
 * it round-trips, and every unreadable state reads as *disarmed* (an unreadable file must not be
 * able to fire a notification, and must not throw inside a fetch loop).
 */
@Category(ShortDuration::class)
class DominantTempWatchStoreTest {

    private fun tempStore(): Pair<DominantTempWatchStore, Path> {
        val dir = Files.createTempDirectory("dominant-temp-watch")
        val path = dir.resolve(DominantTempWatchStore.FILE_NAME)
        return DominantTempWatchStore(path = path) to path
    }

    @Test
    fun `a missing file reads as disarmed`() {
        val (store, _) = tempStore()
        assertEquals(DominantTempWatchState.DISARMED, store.load())
        assertFalse(store.isArmed())
    }

    @Test
    fun `state round-trips`() {
        val (store, _) = tempStore()
        store.save(
            DominantTempWatchState(armed = true, baselineStationId = "KNUQ", baselineTempF = 68.5f),
        )
        val loaded = store.load()
        assertTrue(loaded.armed)
        assertEquals("KNUQ", loaded.baselineStationId)
        assertEquals(68.5f, loaded.baselineTempF!!, 0.0001f)
    }

    @Test
    fun `arming drops any previous baseline`() {
        val (store, _) = tempStore()
        store.save(
            DominantTempWatchState(armed = true, baselineStationId = "KNUQ", baselineTempF = 68f),
        )
        store.setArmed(true)
        val loaded = store.load()
        assertTrue(loaded.armed)
        assertNull(loaded.baselineStationId)
        assertNull(loaded.baselineTempF)
    }

    @Test
    fun `disarming clears everything`() {
        val (store, _) = tempStore()
        store.save(
            DominantTempWatchState(armed = true, baselineStationId = "KNUQ", baselineTempF = 68f),
        )
        store.setArmed(false)
        assertEquals(DominantTempWatchState.DISARMED, store.load())
    }

    @Test
    fun `a corrupt file reads as disarmed instead of throwing`() {
        val (store, path) = tempStore()
        path.writeText("{ this is not json")
        assertEquals(DominantTempWatchState.DISARMED, store.load())
    }

    @Test
    fun `a non-finite persisted baseline is dropped rather than compared against`() {
        val (store, path) = tempStore()
        path.writeText("""{"armed":true,"baselineStationId":"KNUQ","baselineTempF":"NaN"}""")
        val loaded = store.load()
        // Either the decoder rejects the row (disarmed) or it survives with no baseline; both leave
        // nothing for DominantTempWatch to compare a real reading against.
        assertNull(loaded.baselineTempF)
    }
}
