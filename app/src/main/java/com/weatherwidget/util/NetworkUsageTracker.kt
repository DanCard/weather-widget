package com.weatherwidget.util

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import java.util.Locale

data class TrafficBucket(
    val foregroundBytes: Long = 0L,
    val backgroundBytes: Long = 0L,
) {
    val totalBytes: Long get() = foregroundBytes + backgroundBytes

    operator fun plus(other: TrafficBucket): TrafficBucket =
        TrafficBucket(
            foregroundBytes = this.foregroundBytes + other.foregroundBytes,
            backgroundBytes = this.backgroundBytes + other.backgroundBytes,
        )
}

data class NetworkUsageWindow(
    val cellular: TrafficBucket = TrafficBucket(),
    val wifi: TrafficBucket = TrafficBucket(),
)

data class NetworkUsageReport(
    val past24Hours: NetworkUsageWindow,
    val past7Days: NetworkUsageWindow,
    val past30Days: NetworkUsageWindow,
    val past90Days: NetworkUsageWindow,
)

object NetworkUsageTracker {

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun queryNetworkUsage(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): NetworkUsageReport? {
        val nsm = try {
            context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
        } catch (_: Exception) {
            null
        } ?: return null
        val uid = Process.myUid()

        val w24h = nowMs - 24 * 60 * 60 * 1000L
        val w7d = nowMs - 7 * 24 * 60 * 60 * 1000L
        val w30d = nowMs - 30 * 24 * 60 * 60 * 1000L
        val w90d = nowMs - 90 * 24 * 60 * 60 * 1000L

        val (cell24h, cell7d, cell30d, cell90d) =
            queryBuckets(nsm, ConnectivityManager.TYPE_MOBILE, uid, w90d, nowMs, w24h, w7d, w30d)

        val (wifi24h, wifi7d, wifi30d, wifi90d) =
            queryBuckets(nsm, ConnectivityManager.TYPE_WIFI, uid, w90d, nowMs, w24h, w7d, w30d)

        return NetworkUsageReport(
            past24Hours = NetworkUsageWindow(cellular = cell24h, wifi = wifi24h),
            past7Days = NetworkUsageWindow(cellular = cell7d, wifi = wifi7d),
            past30Days = NetworkUsageWindow(cellular = cell30d, wifi = wifi30d),
            past90Days = NetworkUsageWindow(cellular = cell90d, wifi = wifi90d),
        )
    }

    private fun queryBuckets(
        nsm: NetworkStatsManager,
        networkType: Int,
        uid: Int,
        startTimeMs: Long,
        endTimeMs: Long,
        w24h: Long,
        w7d: Long,
        w30d: Long,
    ): Quadruple<TrafficBucket, TrafficBucket, TrafficBucket, TrafficBucket> {
        var b24h = TrafficBucket()
        var b7d = TrafficBucket()
        var b30d = TrafficBucket()
        var b90d = TrafficBucket()

        val networkStats = try {
            nsm.queryDetailsForUid(networkType, null, startTimeMs, endTimeMs, uid)
        } catch (_: Exception) {
            null
        } ?: return Quadruple(b24h, b7d, b30d, b90d)

        try {
            val bucket = NetworkStats.Bucket()
            while (networkStats.hasNextBucket()) {
                networkStats.getNextBucket(bucket)
                val rx = bucket.rxBytes
                val tx = bucket.txBytes
                val total = rx + tx
                if (total <= 0) continue

                val isFg = bucket.state == NetworkStats.Bucket.STATE_FOREGROUND
                val currentBucket = if (isFg) {
                    TrafficBucket(foregroundBytes = total, backgroundBytes = 0L)
                } else {
                    TrafficBucket(foregroundBytes = 0L, backgroundBytes = total)
                }

                val st = bucket.startTimeStamp
                if (st >= w24h) b24h += currentBucket
                if (st >= w7d) b7d += currentBucket
                if (st >= w30d) b30d += currentBucket
                b90d += currentBucket
            }
        } catch (_: Exception) {
            // Ignore partial iteration failures
        } finally {
            try {
                networkStats.close()
            } catch (_: Exception) {}
        }

        return Quadruple(b24h, b7d, b30d, b90d)
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )
}
