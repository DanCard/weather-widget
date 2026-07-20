package com.weatherwidget.shared.util

import kotlin.math.roundToInt

/**
 * Single source of truth for the precision at which daily forecast high/low temps are persisted, so
 * Android (`ForecastRepository.saveForecastSnapshot`) and desktop (`DesktopWeatherDao.upsertForecasts`)
 * store identical values for the same fetch.
 *
 * Rule: **today keeps full decimal precision** (a decimal today high sharpens 1-day-ahead accuracy
 * tracking); **every other day rounds to the nearest integer** to cut future-forecast noise — without
 * this, each tiny sub-degree wiggle in a far-out forecast rewrites the snapshot and jitters the
 * display. See memory `android_future_day_integer_rounding_deliberate`.
 *
 * Non-finite (NaN/Infinity) → null: `roundToInt()` throws "Cannot round NaN value." and the `?.`
 * operator only guards null, so callers must treat a non-finite temp as missing.
 */
object ForecastTempRounding {
    fun forStorage(temp: Float?, isToday: Boolean): Float? {
        val t = temp?.takeIf { it.isFinite() } ?: return null
        return if (isToday) t else t.roundToInt().toFloat()
    }
}
