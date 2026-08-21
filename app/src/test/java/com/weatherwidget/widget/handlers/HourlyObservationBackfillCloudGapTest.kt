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
}
