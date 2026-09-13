package com.weatherwidget.shared.util

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

fun formatNetworkBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
