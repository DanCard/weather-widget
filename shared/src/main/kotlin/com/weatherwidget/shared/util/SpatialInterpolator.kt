package com.weatherwidget.shared.util

import com.weatherwidget.data.model.ObservationReading

object SpatialInterpolator {
    private const val NEAR_ZERO_KM = 0.1f        // treat as "at station" to avoid division by near-zero
    private const val MAX_STALENESS_MS = 3 * 60 * 60 * 1000L  // 3 hours
    private const val MAX_SPREAD_MS = 60 * 60 * 1000L          // observations must be within 1 hour of each other

    private fun timeDecayFactor(ageMs: Long): Double {
        if (ageMs <= 0L) return 1.0
        if (ageMs >= MAX_STALENESS_MS) return 0.0
        return 1.0 - (ageMs.toDouble() / MAX_STALENESS_MS.toDouble())
    }

    /**
     * Blends temperatures from multiple station observations using Inverse Distance Weighting.
     *
     * Returns null if [observations] is empty or all are stale.
     * Returns the observation temperature directly if only one valid observation or a station
     * is within [NEAR_ZERO_KM] of the user.
     */
    fun interpolateIDW(
        userLat: Double,
        userLon: Double,
        observations: List<ObservationReading>,
        nowMs: Long = System.currentTimeMillis(),
    ): Float? {
        val fresh = observations.filter { nowMs - it.timestamp <= MAX_STALENESS_MS }
        if (fresh.isEmpty()) return null

        // Discard outliers in time — keep only those within MAX_SPREAD_MS of the most recent
        val newestMs = fresh.maxOf { it.timestamp }
        val cohort = fresh.filter { newestMs - it.timestamp <= MAX_SPREAD_MS }
        if (cohort.isEmpty()) return null

        // If any station is within NEAR_ZERO_KM, snap to it (closest wins)
        val veryClose = cohort.filter { it.distanceKm <= NEAR_ZERO_KM && timeDecayFactor(nowMs - it.timestamp) > 0.0 }
        if (veryClose.isNotEmpty()) {
            return veryClose.minBy { it.distanceKm }.temperature
        }

        // Single observation — no blending needed, but still check decay
        if (cohort.size == 1) {
            val decay = timeDecayFactor(nowMs - cohort[0].timestamp)
            return if (decay > 0.0) cohort[0].temperature else null
        }

        var weightedTempSum = 0.0
        var weightSum = 0.0
        for (obs in cohort) {
            val ageMs = nowMs - obs.timestamp
            val decay = timeDecayFactor(ageMs)
            if (decay <= 0.0) continue
            val d = obs.distanceKm.toDouble()
            val w = decay / (d * d)
            weightedTempSum += w * obs.temperature
            weightSum += w
        }
        if (weightSum <= 0.0) return null
        return (weightedTempSum / weightSum).toFloat()
    }

    /**
     * Blends arbitrary values from multiple stations using Inverse Distance Weighting.
     * Each entry is a (distanceKm, value) pair. Callers are responsible for staleness filtering.
     */
    fun interpolateIDWValues(stations: List<Pair<Float, Float>>): Float? {
        if (stations.isEmpty()) return null
        val veryClose = stations.filter { it.first <= NEAR_ZERO_KM }
        if (veryClose.isNotEmpty()) return veryClose.minBy { it.first }.second
        if (stations.size == 1) return stations[0].second
        var weightedSum = 0.0
        var weightSum = 0.0
        for ((d, v) in stations) {
            val w = 1.0 / (d.toDouble() * d.toDouble())
            weightedSum += w * v
            weightSum += w
        }
        return (weightedSum / weightSum).toFloat()
    }
}
