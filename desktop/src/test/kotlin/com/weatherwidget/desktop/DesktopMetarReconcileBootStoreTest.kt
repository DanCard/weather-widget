package com.weatherwidget.desktop

import com.weatherwidget.shared.observations.BootInfo
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files

/** The Linux boot id and boot time, and the marker file beside the database. plans/261117-metar-reconcile-once-per-device-boot.md */
@Category(ShortDuration::class)
class DesktopMetarReconcileBootStoreTest {
    @Test
    fun `the boot is the kernel's boot id, begun uptime ago`() {
        val dir = Files.createTempDirectory("boot_store")
        try {
            val bootId = dir.resolve("boot_id").also { Files.writeString(it, "9e13ceb2-8b40-438e-9345-092f66cbf3c8\n") }
            val uptime = dir.resolve("uptime").also { Files.writeString(it, "7200.50 28269.67\n") }
            val store = DesktopMetarReconcileBootStore({ dir.resolve("weather.db.metar-reconcile-boot") }, bootId, uptime)

            assertEquals(BootInfo("9e13ceb2-8b40-438e-9345-092f66cbf3c8", 10_000_000L - 7_200_500L), store.currentBoot(10_000_000L))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no readable boot id means no boot, so the reconcile runs`() {
        val dir = Files.createTempDirectory("boot_store")
        try {
            val store = DesktopMetarReconcileBootStore({ dir.resolve("m") }, dir.resolve("missing"), dir.resolve("missing2"))
            assertEquals(null, store.currentBoot(1L))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `this machine's real boot reads`() {
        val boot = DesktopMetarReconcileBootStore({ Files.createTempFile("m", "") }).currentBoot(System.currentTimeMillis())
        println("boot=$boot")
        assertNotNull(boot)
    }

    @Test
    fun `the marker sits beside its own database`() {
        assertEquals(
            java.nio.file.Path.of("/home/u/.local/share/weather-widget/weather.db.metar-reconcile-boot"),
            DesktopMetarReconcileBootStore.markerFor(java.nio.file.Path.of("/home/u/.local/share/weather-widget/weather.db")),
        )
    }
}
