package com.weatherwidget.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ApiLogEntry(
    val timestamp: Long,
    val apiName: String,  // "NWS" or "Open-Meteo"
    val success: Boolean,
    val errorMessage: String? = null,
    val location: String = "",
    val durationMs: Long = 0
) {
    fun getFormattedTime(): String {
        val instant = Instant.ofEpochMilli(timestamp)
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        return formatter.format(instant)
    }
}

@Singleton
class ApiLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    companion object {
        private const val PREFS_NAME = "api_log_prefs"
        private const val KEY_LOG_ENTRIES = "log_entries"
        private const val MAX_ENTRIES = 100
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun logApiCall(
        apiName: String,
        success: Boolean,
        errorMessage: String? = null,
        location: String = "",
        durationMs: Long = 0
    ) {
        android.util.Log.d("ApiLogger", "logApiCall: $apiName, success=$success, error=$errorMessage")
        val entries = getLogEntries().toMutableList()
        entries.add(0, ApiLogEntry(
            timestamp = System.currentTimeMillis(),
            apiName = apiName,
            success = success,
            errorMessage = errorMessage,
            location = location,
            durationMs = durationMs
        ))

        // Keep only the most recent entries
        val trimmed = entries.take(MAX_ENTRIES)

        try {
            prefs.edit()
                .putString(KEY_LOG_ENTRIES, json.encodeToString(trimmed))
                .apply()
            android.util.Log.d("ApiLogger", "Successfully saved ${trimmed.size} entries")
        } catch (e: Exception) {
            android.util.Log.e("ApiLogger", "Failed to save log entries", e)
        }
    }

    fun getLogEntries(): List<ApiLogEntry> {
        val jsonString = prefs.getString(KEY_LOG_ENTRIES, null) ?: return emptyList()
        return try {
            json.decodeFromString(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearLog() {
        prefs.edit().remove(KEY_LOG_ENTRIES).apply()
    }
}
