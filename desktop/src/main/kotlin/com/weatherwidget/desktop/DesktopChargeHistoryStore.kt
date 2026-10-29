package com.weatherwidget.desktop

import com.weatherwidget.shared.util.ChargeHistory
import com.weatherwidget.shared.util.ChargeHistoryStore
import com.weatherwidget.shared.util.Log
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * File-backed [ChargeHistoryStore] for the daemon, the only desktop process that watches the charger
 * (Android keeps the same state in `charge_session_prefs`). A notebook leaves AC like a phone leaves its
 * charger, so it learns the same unplug habits (plans/261028-learned-unplug-prediction.md). An
 * unreadable file starts an empty history, which at worst postpones a prediction.
 */
class DesktopChargeHistoryStore(private val file: Path) : ChargeHistoryStore {
    override fun load(): ChargeHistory.State =
        try {
            ChargeHistory.State.decode(Files.readString(file, StandardCharsets.UTF_8).trim()) ?: ChargeHistory.State()
        } catch (e: NoSuchFileException) {
            ChargeHistory.State()
        } catch (e: Exception) {
            Log.w(TAG, "Charge history unreadable, starting empty: ${e.message}")
            ChargeHistory.State()
        }

    override fun save(state: ChargeHistory.State) {
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, state.encode(), StandardCharsets.UTF_8)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Log.w(TAG, "Charge history save failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DesktopChargeHistory"

        fun default(): DesktopChargeHistoryStore = DesktopChargeHistoryStore(appDataDir().resolve("charge_history"))
    }
}
