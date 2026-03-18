package com.weatherwidget.util

import com.weatherwidget.data.local.ObservationEntity

object SpatialInterpolator {
    private const val NEAR_ZERO_KM = 0.1f        // treat as "at station" to avoid division by near-zero
    private const val MAX_STALENESS_MS = 2 * 60 * 60 * 1000L  // 2 hours
    private const val MAX_SPREAD_MS = 60 * 60 * 1000L          // observations must be within 1 hour of each other

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
        observations: List<ObservationEntity>,
        nowMs: Long = System.currentTimeMillis(),
    ): Float? {
        val fresh = observations.filter { nowMs - it.timestamp <= MAX_STALENESS_MS }
        if (fresh.isEmpty()) return null

        // Discard outliers in time — keep only those within MAX_SPREAD_MS of the most recent
        val newestMs = fresh.maxOf { it.timestamp }
        val cohort = fresh.filter { newestMs - it.timestamp <= MAX_SPREAD_MS }
        if (cohort.isEmpty()) return null

        // If any station is within NEAR_ZERO_KM, snap to it (closest wins)
        val veryClose = cohort.filter { it.distanceKm <= NEAR_ZERO_KM }
        if (veryClose.isNotEmpty()) {
            return veryClose.minBy { it.distanceKm }.temperature
        }

        // Single observation — no blending needed
        if (cohort.size == 1) return cohort[0].temperature

        // IDW: w_i = 1/d_i²,  T = Σ(w_i * T_i) / Σ(w_i)
        var weightedTempSum = 0.0
        var weightSum = 0.0
        for (obs in cohort) {
            val d = obs.distanceKm.toDouble()
            val w = 1.0 / (d * d)
            weightedTempSum += w * obs.temperature
            weightSum += w
        }
        return (weightedTempSum / weightSum).toFloat()
    }
}
