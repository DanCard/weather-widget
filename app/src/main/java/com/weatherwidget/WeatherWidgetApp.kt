package com.weatherwidget

import android.app.Application
import android.os.SystemClock
import com.weatherwidget.widget.OpportunisticUpdateJobService
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WeatherWidgetApp : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        processStartElapsedRealtime = SystemClock.elapsedRealtime()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            OpportunisticUpdateJobService.scheduleOpportunisticUpdate(this)
        }
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()

    companion object {
        @Volatile
        private var processStartElapsedRealtime: Long = 0L

        fun processAgeMs(nowElapsedRealtime: Long = SystemClock.elapsedRealtime()): Long {
            val start = processStartElapsedRealtime
            return if (start > 0L) (nowElapsedRealtime - start).coerceAtLeast(0L) else Long.MAX_VALUE
        }
    }
}
