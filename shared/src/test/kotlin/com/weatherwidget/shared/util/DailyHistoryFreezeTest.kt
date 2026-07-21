package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyHistoryFreezeTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val date = LocalDate.of(2026, 7, 1)

    private fun ms(d: LocalDate, hour: Int, minute: Int = 0) =
        d.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val empty = DailyHistoryFreeze.FrozenDisplay(null, null, null, null)
    private val frozen = DailyHistoryFreeze.FrozenDisplay(75f, 50f, 3.2f, 60)

    @Test
    fun `overlay window is open through the day and closes at local midnight`() {
        assertTrue(DailyHistoryFreeze.overlayWindowOpen(ms(date, 0, 1), date, zone))
        assertTrue(DailyHistoryFreeze.overlayWindowOpen(ms(date, 23, 59), date, zone))
        assertFalse(DailyHistoryFreeze.overlayWindowOpen(ms(date.plusDays(1), 0), date, zone))
        assertFalse(DailyHistoryFreeze.overlayWindowOpen(ms(date.plusDays(1), 12), date, zone))
    }

    @Test
    fun `noon cloud window stays open until 8am the next day for late-arriving data`() {
        assertTrue(DailyHistoryFreeze.noonCloudWindowOpen(ms(date, 12), date, zone))
        assertTrue(DailyHistoryFreeze.noonCloudWindowOpen(ms(date.plusDays(1), 7, 59), date, zone))
        assertFalse(DailyHistoryFreeze.noonCloudWindowOpen(ms(date.plusDays(1), 8), date, zone))
    }

    @Test
    fun `complete overlay overwrites frozen values while window is open`() {
        val merged = DailyHistoryFreeze.merge(
            overlayOpen = true, noonCloudOpen = true,
            resolvedHigh = 80f, resolvedLow = 55f, resolvedPrecipAmountMm = 1.5f,
            resolvedNoonCloudPercent = 40, existing = frozen,
        )
        assertEquals(DailyHistoryFreeze.FrozenDisplay(80f, 55f, 1.5f, 40), merged)
    }

    @Test
    fun `first write populates an empty row`() {
        val merged = DailyHistoryFreeze.merge(
            overlayOpen = true, noonCloudOpen = true,
            resolvedHigh = 80f, resolvedLow = 55f, resolvedPrecipAmountMm = 1.5f,
            resolvedNoonCloudPercent = 40, existing = empty,
        )
        assertEquals(DailyHistoryFreeze.FrozenDisplay(80f, 55f, 1.5f, 40), merged)
    }

    @Test
    fun `incomplete overlay keeps the existing frozen pair`() {
        // NWS evening batches drop lowTemp once the day's low has passed — the earlier complete
        // batch's frozen values must survive.
        val merged = DailyHistoryFreeze.merge(
            overlayOpen = true, noonCloudOpen = true,
            resolvedHigh = 80f, resolvedLow = null, resolvedPrecipAmountMm = 1.5f,
            resolvedNoonCloudPercent = null, existing = frozen,
        )
        assertEquals(frozen, merged)
    }

    @Test
    fun `closed windows keep frozen values even when new resolutions exist`() {
        val merged = DailyHistoryFreeze.merge(
            overlayOpen = false, noonCloudOpen = false,
            resolvedHigh = 80f, resolvedLow = 55f, resolvedPrecipAmountMm = 1.5f,
            resolvedNoonCloudPercent = 40, existing = frozen,
        )
        assertEquals(frozen, merged)
    }

    @Test
    fun `null resolutions never regress frozen values to null`() {
        val merged = DailyHistoryFreeze.merge(
            overlayOpen = true, noonCloudOpen = true,
            resolvedHigh = null, resolvedLow = null, resolvedPrecipAmountMm = null,
            resolvedNoonCloudPercent = null, existing = frozen,
        )
        assertEquals(frozen, merged)
    }

    @Test
    fun `complete overlay with null amount keeps the previously frozen amount`() {
        val merged = DailyHistoryFreeze.merge(
            overlayOpen = true, noonCloudOpen = true,
            resolvedHigh = 80f, resolvedLow = 55f, resolvedPrecipAmountMm = null,
            resolvedNoonCloudPercent = null, existing = frozen,
        )
        assertEquals(DailyHistoryFreeze.FrozenDisplay(80f, 55f, 3.2f, 60), merged)
    }
}
