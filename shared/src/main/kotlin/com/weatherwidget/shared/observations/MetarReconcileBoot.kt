package com.weatherwidget.shared.observations

import com.weatherwidget.data.local.LocationMatch

/**
 * One boot of the device or computer: [id] changes on every boot (Android `Settings.Global.BOOT_COUNT`,
 * Linux `/proc/sys/kernel/random/boot_id`), [bootedAtMs] is wall-clock time at boot.
 */
data class BootInfo(val id: String, val bootedAtMs: Long)

/**
 * Where a platform keeps which boot last finished [NwsGapFillRun.reconcileMetars] for a site, so the
 * reconcile runs once per device boot (user, 2026-11-17: "I meant phone start, not app start").
 * Android keeps it in a small preferences file, desktop in a file beside its database: no schema.
 * plans/261117-metar-reconcile-once-per-device-boot.md
 */
interface MetarReconcileBootStore {
    /** The current boot, or null when the platform can't tell (the reconcile then always runs). */
    fun currentBoot(nowMs: Long): BootInfo?

    fun reconciledBootId(siteKey: String): String?

    fun markReconciled(siteKey: String, bootId: String)

    companion object {
        /** One key per site, by the shared [LocationMatch] quantization. */
        fun siteKey(latitude: Double, longitude: Double): String =
            "${LocationMatch.quantize(latitude)},${LocationMatch.quantize(longitude)}"
    }
}
