package com.weatherwidget.desktop

import com.weatherwidget.shared.observations.BootInfo
import com.weatherwidget.shared.observations.MetarReconcileBootStore
import com.weatherwidget.shared.util.Log
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Desktop half of [MetarReconcileBootStore]: the Linux boot id (`/proc/sys/kernel/random/boot_id`),
 * the boot time from `/proc/uptime`, and a small properties file beside the database ([markerPath]),
 * one `siteKey=bootId` line per site. Android's is `AndroidMetarReconcileBootStore`.
 * plans/261117-metar-reconcile-once-per-device-boot.md
 */
internal class DesktopMetarReconcileBootStore(
    private val markerPath: () -> Path,
    private val bootIdFile: Path = Path.of("/proc/sys/kernel/random/boot_id"),
    private val uptimeFile: Path = Path.of("/proc/uptime"),
) : MetarReconcileBootStore {
    override fun currentBoot(nowMs: Long): BootInfo? = runCatching {
        val id = Files.readString(bootIdFile).trim()
        val uptimeMs = (Files.readString(uptimeFile).trim().substringBefore(' ').toDouble() * 1000).toLong()
        BootInfo(id, nowMs - uptimeMs).takeIf { id.isNotEmpty() }
    }.onFailure { Log.w(TAG, "No boot id: $it") }.getOrNull()

    override fun reconciledBootId(siteKey: String): String? = load().getProperty(siteKey)

    @Synchronized
    override fun markReconciled(siteKey: String, bootId: String) {
        val path = markerPath()
        val props = load().apply { setProperty(siteKey, bootId) }
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.newBufferedWriter(tmp).use { props.store(it, "Boot that last reconciled NWS rows with METARs, per site") }
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun load(): Properties = Properties().also { props ->
        val path = runCatching { markerPath() }.getOrNull() ?: return@also
        if (Files.exists(path)) {
            runCatching { Files.newBufferedReader(path).use(props::load) }
                .onFailure { Log.w(TAG, "Unreadable $path ($it); reconciling again") }
        }
    }

    companion object {
        private const val TAG = "DesktopMetarReconcileBootStore"

        /** Beside [databasePath], named after it so each database (and each test's) has its own. */
        fun markerFor(databasePath: Path): Path = databasePath.resolveSibling("${databasePath.fileName}.metar-reconcile-boot")
    }
}
