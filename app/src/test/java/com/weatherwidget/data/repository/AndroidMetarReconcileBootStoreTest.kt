package com.weatherwidget.data.repository

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.shared.observations.BootInfo
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** `BOOT_COUNT` is the boot; wall clock minus elapsedRealtime is when it began. plans/261117-metar-reconcile-once-per-device-boot.md */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class AndroidMetarReconcileBootStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the boot is BOOT_COUNT, begun elapsedRealtime ago`() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 126)
        val now = 1_800_000_000_000L

        val boot = AndroidMetarReconcileBootStore(context).currentBoot(now)

        println("boot=$boot elapsed=${SystemClock.elapsedRealtime()}")
        assertEquals(BootInfo("126", now - SystemClock.elapsedRealtime()), boot)
    }

    @Test
    fun `no BOOT_COUNT means no boot, so the reconcile runs`() {
        assertEquals(null, AndroidMetarReconcileBootStore(context).currentBoot(1L))
    }

    @Test
    fun `the mark survives a new store`() {
        AndroidMetarReconcileBootStore(context).markReconciled("37.417,-122.089", "126")
        assertEquals("126", AndroidMetarReconcileBootStore(context).reconciledBootId("37.417,-122.089"))
    }
}
