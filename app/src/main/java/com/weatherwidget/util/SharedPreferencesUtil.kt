package com.weatherwidget.util

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.data.local.WeatherDatabase

object SharedPreferencesUtil {
    fun getPrefsName(name: String): String {
        // Append _test_default if in testing mode, unless it's already an isolated test pref
        if (WeatherDatabase.isTestingMode() && !name.contains("_test")) {
            return "${name}_test_default"
        }
        return name
    }

    fun getPrefs(context: Context, name: String): SharedPreferences {
        return context.getSharedPreferences(getPrefsName(name), Context.MODE_PRIVATE)
    }
}
