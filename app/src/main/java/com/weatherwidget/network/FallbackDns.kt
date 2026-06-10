package com.weatherwidget.network

import android.content.SharedPreferences
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * OkHttp DNS resolver that persists successful lookups to SharedPreferences.
 * On UnknownHostException, falls back to the cached IPs (up to CACHE_TTL_MS old).
 * This survives app restarts and protects against transient DNS outages.
 */
class FallbackDns(private val prefs: SharedPreferences) : Dns {

    companion object {
        const val PREFS_NAME = "dns_cache_prefs"
        private const val KEY_IPS_PREFIX = "dns_ips_"
        private const val KEY_TIME_PREFIX = "dns_time_"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    }

    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (addresses.isNotEmpty()) persist(hostname, addresses)
            }
        } catch (e: UnknownHostException) {
            loadCache(hostname) ?: throw e
        }
    }

    private fun persist(hostname: String, addresses: List<InetAddress>) {
        val ips = addresses.mapNotNull { it.hostAddress }.joinToString(",")
        prefs.edit()
            .putString("$KEY_IPS_PREFIX$hostname", ips)
            .putLong("$KEY_TIME_PREFIX$hostname", System.currentTimeMillis())
            .apply()
    }

    private fun loadCache(hostname: String): List<InetAddress>? {
        val savedAt = prefs.getLong("$KEY_TIME_PREFIX$hostname", 0L)
        if (System.currentTimeMillis() - savedAt > CACHE_TTL_MS) return null
        val ips = prefs.getString("$KEY_IPS_PREFIX$hostname", null) ?: return null
        return ips.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            .takeIf { it.isNotEmpty() }
    }
}
