package com.weatherwidget.util

import android.content.Context
import android.net.ConnectivityManager

object NetworkRestrictionHelper {
    /**
     * Checks if background data usage on the current network is restricted by the OS
     * (either app-specific metered background restriction or global Data Saver without whitelisting).
     */
    fun isBackgroundDataRestricted(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.isActiveNetworkMetered && cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }
}
