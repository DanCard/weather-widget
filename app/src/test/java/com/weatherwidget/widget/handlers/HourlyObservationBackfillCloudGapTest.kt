package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins which rows form the basis of the METAR cloud-sparsity check.
 *
 * The basis is the whole check: get it wrong and the check reports on a subset that cannot represent
 * the curve. Until 2026-08-21 it excluded `isWebFallback` rows on the false premise that Synoptic
 * cannot carry sky condition, so KNUQ — the nearest official station, degraded to web fallback —
 * contributed 198 cloud-less rows that were invisible here. The check read a healthy 42/66 while
 * every cloud value on the device came from a station 15.9 km away.
 */
@Category(ShortDuration::class)
class HourlyObservationBackfillCloudGapTest {

    private val hour = 3_600_000L

    private fun row(
        bucket: Int,
        stationType: String = "OFFICIAL",
        cloudCoverLow: Int? = null,
        isWebFallback: Boolean = false,
        qcFailed: Boolean = false,
        stationId: String = "KNUQ",
    ) = ObservationEntity(
        stationId = stationId,
        stationName = stationId,
        // Mid-hour, so round-to-nearest bucketing lands where the test intends.
        timestamp = bucket * hour + 30 * 60_000L,
        temperature = 60f,
        condition = "Clear",
        locationLat = 37.417,
        locationLon = -122.089,
        stationType = stationType,
        api = "NWS",
        isWebFallback = isWebFallback,
        qcFailed = qcFailed,
        cloudCoverLow = cloudCoverLow,
    )

    @Test
    fun `web-fallback rows carrying cloud count toward coverage`() {
        // 10 buckets, all web fallback, all with sky condition: a healthy series that happens to be
        // sourced from Synoptic. Excluding these used to make the check blind to its best station.
        val rows = (0 until 10).map { row(bucket = it, cloudCoverLow = 100, isWebFallback = true) }
        assertNull(metarCloudGapReason(rows))
    }

    /**
     * The regression the exclusion hid. Ten official buckets, none carrying cloud, all arriving via
     * web fallback — the exact KNUQ shape. This must now be reported as broken.
     */
    @Test
    fun `web-fallback rows with no cloud now trip the repair`() {
        val rows = (0 until 10).map { row(bucket = it, cloudCoverLow = null, isWebFallback = true) }
        val reason = metarCloudGapReason(rows)
        assertNotNull("a cloud-less official series must be reported", reason)
        assertTrue(reason!!.startsWith("metar_cloud_sparse"))
        assertTrue(reason.contains("cloudBuckets=0"))
        assertTrue(reason.contains("officialBuckets=10"))
    }

    @Test
    fun `personal stations stay out of the basis`() {
        // No ceilometer, so they report empty sky condition on every report and could never satisfy
        // the check. Counting them would keep the repair firing forever.
        val rows = (0 until 10).map {
            row(bucket = it, stationType = "PERSONAL", cloudCoverLow = null)
        }
        assertNull(metarCloudGapReason(rows))
    }

    @Test
    fun `a personal station cannot drag an otherwise healthy official series down`() {
        val official = (0 until 6).map { row(bucket = it, cloudCoverLow = 75) }
        val personal = (10 until 40).map {
            row(bucket = it, stationType = "PERSONAL", cloudCoverLow = null, stationId = "AW020")
        }
        assertNull(metarCloudGapReason(official + personal))
    }

    @Test
    fun `qc-failed rows stay out of the basis`() {
        val rows = (0 until 10).map { row(bucket = it, cloudCoverLow = null, qcFailed = true) }
        assertNull(metarCloudGapReason(rows))
    }

    @Test
    fun `an all-official cloudless series still fires`() {
        val rows = (0 until 8).map { row(bucket = it, cloudCoverLow = null) }
        assertNotNull(metarCloudGapReason(rows))
    }

    @Test
    fun `the threshold is strictly below half the official buckets`() {
        // 5 of 10 covered — exactly half — is healthy; 4 of 10 is not.
        val half = (0 until 10).map { row(bucket = it, cloudCoverLow = if (it < 5) 100 else null) }
        assertNull("exactly half covered is not sparse", metarCloudGapReason(half))

        val belowHalf = (0 until 10).map { row(bucket = it, cloudCoverLow = if (it < 4) 100 else null) }
        assertNotNull("below half is sparse", metarCloudGapReason(belowHalf))
    }

    @Test
    fun `no official rows at all is not a cloud verdict`() {
        // Nothing to judge — must stay silent rather than demand a repair it cannot evaluate.
        assertNull(metarCloudGapReason(emptyList()))
        assertNull(metarCloudGapReason(listOf(row(bucket = 0, stationType = "PERSONAL"))))
    }

    @Test
    fun `the total column satisfies the check when the low layer is absent`() {
        // The check reads cloudCoverLow ?: cloudCover, so a non-NWS row carrying only a total still
        // counts. Pins that the fallback arm is live.
        val rows = (0 until 10).map {
            row(bucket = it).copy(cloudCover = 50, cloudCoverLow = null)
        }
        assertNull(metarCloudGapReason(rows))
    }

    // ── metarCloudBreakReason: a HOLE in the curve, not a sparse series ───────────────────

    private fun carrier(minutesFromStart: Int, stationId: String = "KNUQ", qcFailed: Boolean = false) =
        ObservationEntity(
            stationId = stationId,
            stationName = stationId,
            timestamp = 1_800_000_000_000L + minutesFromStart * 60_000L,
            temperature = 60f,
            condition = "Clear",
            locationLat = 37.417,
            locationLon = -122.089,
            stationType = "OFFICIAL",
            api = "NWS",
            isWebFallback = false,
            qcFailed = qcFailed,
            cloudCoverLow = 75,
        )

    /** A temperature-only row: present in the window, invisible to the cloud curve. */
    private fun tempOnly(minutesFromStart: Int, stationId: String = "AW020") =
        carrier(minutesFromStart, stationId).copy(cloudCoverLow = null, stationType = "PERSONAL")

    /**
     * The Samsung's cloud-carrying observation times on 2026-09-03, minutes from 02:53. The curve
     * broke at 11:35 -> 12:15 (the 522 -> 562 step) while the phone was in a pocket at basketball.
     */
    private val samsungCarrierMinutes = listOf(
        0, 2, 22, 27, 37, 42, 52, 62, 72, 82, 92, 102, 117, 122, 127, 142, 147, 157, 162, 167,
        182, 192, 202, 207, 212, 222, 232, 240, 242, 252, 262, 267, 272, 282, 287, 294, 297, 302,
        317, 322, 332, 342, 357, 362, 372, 382, 387, 392, 402, 412, 414, 422, 432, 442, 462, 474,
        477, 482, 492, 502, 512, 517, 522, 562, 572, 582, 594, 602, 612, 617, 622, 632, 642, 647,
        654, 657, 662, 672, 677, 682, 692, 702,
    )

    @Test
    fun `the samsung 2026-09-03 curve break requests a backfill`() {
        val reason = metarCloudBreakReason(samsungCarrierMinutes.map { carrier(it) })

        assertNotNull("a 40-minute hole past a 30-minute bridge must re-fetch", reason)
        assertTrue(reason!!, reason.startsWith("cloud_series_break_min=40"))
        assertTrue(reason, reason.contains("bridge_min=30"))
    }

    @Test
    fun `a dense five-minute feed over the same window stays silent`() {
        // The emulator's shape: KSJC's ASOS samples covered every 5 minutes, so nothing broke.
        val rows = (0..140).map { carrier(it * 5, stationId = "KSJC") }

        assertNull(metarCloudBreakReason(rows))
    }

    @Test
    fun `dense temperature rows cannot mask a hole in the cloud curve`() {
        // This is the blindness the gate exists to close. Temperature rows every 5 minutes across
        // the hole made the old max-gap check read 23 minutes while the cloud curve was split.
        val carriers = listOf(0, 10, 20, 30, 40, 50, 60, 100, 110, 120).map { carrier(it) }
        val fillers = (0..120 step 5).map { tempOnly(it) }

        assertNotNull(metarCloudBreakReason(carriers + fillers))
    }

    @Test
    fun `qc-failed rows are not part of the basis`() {
        // A qc-failed row never enters the blend, so it must not be allowed to close a hole here.
        val carriers = listOf(0, 10, 20, 30, 40, 50, 60, 100, 110, 120).map { carrier(it) }
        val bogus = carrier(80, qcFailed = true)

        assertNotNull(metarCloudBreakReason(carriers + bogus))
    }

    @Test
    fun `a sparse but unbroken hourly series is not a break`() {
        // Sparse is not broken: every report this station makes is present.
        val rows = (0..8).map { carrier(it * 60) }

        assertNull(metarCloudBreakReason(rows))
    }

    @Test
    fun `no cloud-carrying rows at all is left to the sparsity check`() {
        assertNull(metarCloudBreakReason((0..10).map { tempOnly(it * 10) }))
    }
}
