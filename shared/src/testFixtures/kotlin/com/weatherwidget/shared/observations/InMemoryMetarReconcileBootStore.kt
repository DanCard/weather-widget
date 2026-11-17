package com.weatherwidget.shared.observations

/**
 * A [MetarReconcileBootStore] for fakes: [boot] is the current boot (settled 3 h ago by default, so the
 * first answered reconcile marks it done), and [marks] the stored boot per site.
 */
class InMemoryMetarReconcileBootStore(
    var boot: ((nowMs: Long) -> BootInfo?)? = { now -> BootInfo("boot-1", now - 3 * 60 * 60 * 1000L) },
) : MetarReconcileBootStore {
    val marks = mutableMapOf<String, String>()

    override fun currentBoot(nowMs: Long): BootInfo? = boot?.invoke(nowMs)

    override fun reconciledBootId(siteKey: String): String? = marks[siteKey]

    override fun markReconciled(siteKey: String, bootId: String) {
        marks[siteKey] = bootId
    }
}
