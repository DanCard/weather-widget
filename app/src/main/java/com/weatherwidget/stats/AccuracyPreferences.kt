package com.weatherwidget.stats

import android.content.Context
import com.weatherwidget.shared.stats.AccuracyBaselineField
import com.weatherwidget.util.SharedPreferencesUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists which actual the statistics screen scores forecasts against.
 *
 * Display-only: both values are always stored in `daily_history`, so flipping this recomputes the
 * statistics from data already on disk — no refetch, and no rewriting of history.
 */
@Singleton
class AccuracyPreferences
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val prefs by lazy { SharedPreferencesUtil.getPrefs(context, PREFS_NAME) }

        fun baselineField(): AccuracyBaselineField =
            AccuracyBaselineField.fromPrefValue(prefs.getString(KEY_BASELINE_FIELD, null))

        fun setBaselineField(field: AccuracyBaselineField) {
            prefs.edit().putString(KEY_BASELINE_FIELD, field.prefValue).apply()
        }

        private companion object {
            const val PREFS_NAME = "weather_prefs"
            const val KEY_BASELINE_FIELD = "accuracy_baseline_field"
        }
    }
