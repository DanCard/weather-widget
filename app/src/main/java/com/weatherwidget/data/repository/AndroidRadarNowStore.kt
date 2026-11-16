package com.weatherwidget.data.repository

import android.content.Context
import com.weatherwidget.shared.observations.RadarNowSample
import com.weatherwidget.shared.observations.RadarNowSampler

/**
 * Android's [RadarNowSampler.Store]: the last radar sample in SharedPreferences, because the worker
 * that samples and the renderer that reads are separate passes (often separate processes' lifetimes).
 */
class AndroidRadarNowStore(context: Context) : RadarNowSampler.Store {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): RadarNowSample? = RadarNowSample.decode(prefs.getString(KEY, null))

    override fun write(sample: RadarNowSample) {
        prefs.edit().putString(KEY, sample.encode()).apply()
    }

    private companion object {
        const val PREFS = "radar_now"
        const val KEY = "last_sample"
    }
}
