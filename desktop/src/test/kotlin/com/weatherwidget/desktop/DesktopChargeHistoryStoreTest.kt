package com.weatherwidget.desktop

import com.weatherwidget.shared.util.ChargeHistory
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files

/** plans/261028-learned-unplug-prediction.md */
@Category(ShortDuration::class)
class DesktopChargeHistoryStoreTest {
    private val dir = Files.createTempDirectory("charge-history-test")
    private val file = dir.resolve("charge_history")

    @Test
    fun `history round-trips through the file, and a missing or corrupt file starts empty`() {
        val store = DesktopChargeHistoryStore(file)
        assertEquals(ChargeHistory.State(), store.load())
        val state = ChargeHistory.State(
            sessions = listOf(ChargeHistory.Session(1_000L, 9_000_000L, exact = true)),
            chargingSinceMs = 10_000_000L,
            lastSeenChargingMs = 10_300_000L,
        )
        store.save(state)
        assertEquals(state, DesktopChargeHistoryStore(file).load())
        Files.writeString(file, "not a history")
        assertEquals(ChargeHistory.State(), store.load())
    }
}
