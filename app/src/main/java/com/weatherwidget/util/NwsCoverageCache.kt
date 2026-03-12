package com.weatherwidget.util

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.remote.NwsApi
import kotlin.math.roundToInt

object NwsCoverageCache {
    private const val PREFS_NAME = "nws_coverage_cache"
    private const val KEY_STATUS_PREFIX = "status_"
    private const val KEY_CHECKED_AT_PREFIX = "checked_at_"
    private const val STATUS_COVERED = "covered"
    private const val STATUS_UNCOVERED = "uncovered"
    private const val UNCOVERED_RECHECK_MS = 6 * 60 * 60 * 1000L
    private const val COVERAGE_BUCKET_SCALE = 10

    fun isCoveredForDisplay(
        context: Context,
        latitude: Double,
        longitude: Double,
    ): Boolean {
        if (getCachedStatus(context, latitude, longitude) == STATUS_COVERED) {
            return true
        }

        val prefs = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
        val nearbyStatuses =
            neighborKeys(latitude, longitude)
                .map { key -> prefs.getString("$KEY_STATUS_PREFIX$key", null) }
        return nearbyStatuses.any { it == STATUS_COVERED }
    }

    suspend fun isNwsCovered(
        context: Context,
        nwsApi: NwsApi,
        appLogDao: AppLogDao?,
        latitude: Double,
        longitude: Double,
    ): Boolean {
        val cachedStatus = getCachedStatus(context, latitude, longitude)
        val checkedAt = getCheckedAt(context, latitude, longitude)
        if (cachedStatus == STATUS_COVERED) {
            return true
        }
        if (cachedStatus == STATUS_UNCOVERED && System.currentTimeMillis() - checkedAt < UNCOVERED_RECHECK_MS) {
            return false
        }

        return try {
            nwsApi.getGridPoint(latitude, longitude)
            setCachedStatus(context, latitude, longitude, STATUS_COVERED)
            appLogDao?.log("NWS_COVERAGE", "lat=$latitude lon=$longitude status=covered")
            true
        } catch (exception: Exception) {
            if (cachedStatus == STATUS_COVERED) {
                appLogDao?.log(
                    "NWS_COVERAGE_WARN",
                    "lat=$latitude lon=$longitude probe_error=${exception.message} sticky=covered",
                    "WARN",
                )
                true
            } else {
                setCachedStatus(context, latitude, longitude, STATUS_UNCOVERED)
                appLogDao?.log(
                    "NWS_COVERAGE",
                    "lat=$latitude lon=$longitude status=uncovered error=${exception.message}",
                    "INFO",
                )
                false
            }
        }
    }

    private fun getCachedStatus(
        context: Context,
        latitude: Double,
        longitude: Double,
    ): String? {
        val prefs = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
        return prefs.getString("${KEY_STATUS_PREFIX}${locationKey(latitude, longitude)}", null)
    }

    private fun getCheckedAt(
        context: Context,
        latitude: Double,
        longitude: Double,
    ): Long {
        val prefs = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
        return prefs.getLong("${KEY_CHECKED_AT_PREFIX}${locationKey(latitude, longitude)}", 0L)
    }

    private fun setCachedStatus(
        context: Context,
        latitude: Double,
        longitude: Double,
        status: String,
    ) {
        val key = locationKey(latitude, longitude)
        val prefs = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
        prefs.edit()
            .putString("$KEY_STATUS_PREFIX$key", status)
            .putLong("$KEY_CHECKED_AT_PREFIX$key", System.currentTimeMillis())
            .apply()
    }

    private fun locationKey(
        latitude: Double,
        longitude: Double,
    ): String {
        val latBucket = (latitude * COVERAGE_BUCKET_SCALE).roundToInt() / COVERAGE_BUCKET_SCALE.toDouble()
        val lonBucket = (longitude * COVERAGE_BUCKET_SCALE).roundToInt() / COVERAGE_BUCKET_SCALE.toDouble()
        return "${latBucket}_${lonBucket}"
    }

    private fun neighborKeys(
        latitude: Double,
        longitude: Double,
    ): List<String> {
        val baseLat = (latitude * COVERAGE_BUCKET_SCALE).roundToInt()
        val baseLon = (longitude * COVERAGE_BUCKET_SCALE).roundToInt()
        val keys = mutableListOf<String>()
        for (latOffset in -1..1) {
            for (lonOffset in -1..1) {
                val latBucket = (baseLat + latOffset) / COVERAGE_BUCKET_SCALE.toDouble()
                val lonBucket = (baseLon + lonOffset) / COVERAGE_BUCKET_SCALE.toDouble()
                keys += "${latBucket}_${lonBucket}"
            }
        }
        return keys.distinct()
    }
}
