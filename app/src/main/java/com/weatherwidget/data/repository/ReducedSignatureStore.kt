package com.weatherwidget.data.repository

import android.content.Context
import com.weatherwidget.shared.actuals.ReducedSignatureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signatures that survive the process. Reads are served from memory after the first touch; each
 * write goes through to a dedicated preference file (`apply`, off the caller's thread). Keys are
 * `date|lat|lon` and values are short digest strings, so the file stays a few KB.
 */
@Singleton
class PersistedReducedSignatureStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ReducedSignatureStore {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val memory = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    private var loaded = false

    private fun loadOnce() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            for ((key, value) in prefs.all) {
                if (value is String) memory[key] = value
            }
            loaded = true
        }
    }

    override fun get(key: String): String? {
        loadOnce()
        return memory[key]
    }

    override fun set(key: String, signature: String) {
        loadOnce()
        memory[key] = signature
        prefs.edit().putString(key, signature).apply()
    }

    override fun clear() {
        loadOnce()
        memory.clear()
        prefs.edit().clear().apply()
    }

    override val size: Int
        get() {
            loadOnce()
            return memory.size
        }

    companion object {
        const val PREFS_NAME = "daily_recompute_signatures"
    }
}
