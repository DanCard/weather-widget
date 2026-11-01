package com.weatherwidget.data.model

import com.weatherwidget.shared.graph.CloudBands
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What a view reads from [HourlyActual] rows, keyed like its live series. Archived hours fill in
 * behind the live blend, which always wins where raw rows remain: an archived row is a frozen result,
 * so it is never turned back into an observation and re-blended.
 */
object HourlyActualsHistory {
    fun temperatures(rows: List<HourlyActual>): Map<Long, Float> =
        rows.mapNotNull { row -> row.temperature?.let { row.hourMs to it } }.toMap()

    fun rainByHour(rows: List<HourlyActual>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDateTime, Float> =
        rows.mapNotNull { row ->
            row.precipAmountMm?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(row.hourMs), zone) to it }
        }.toMap()

    fun cloud(rows: List<HourlyActual>): Map<Long, Int> =
        rows.mapNotNull { row -> row.cloudCover?.let { row.hourMs to it } }.toMap()

    fun cloudBands(rows: List<HourlyActual>): Map<Long, CloudBands> =
        rows.associate { it.hourMs to CloudBands(it.cloudCoverLow, it.cloudCoverMid, it.cloudCoverHigh) }
            .filterValues { !it.isEmpty }

    /** [archived] behind [live]; a live hour is never replaced. */
    fun <K, V> merge(live: Map<K, V>, archived: Map<K, V>): Map<K, V> = archived + live
}
