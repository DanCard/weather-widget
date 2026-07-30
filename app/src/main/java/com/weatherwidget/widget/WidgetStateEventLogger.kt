package com.weatherwidget.widget

import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun interface WidgetStateEventLogger {
    fun log(tag: String, message: String)
}

object NoOpWidgetStateEventLogger : WidgetStateEventLogger {
    override fun log(tag: String, message: String) = Unit
}

class AppLogWidgetStateEventLogger(
    private val appLogDao: AppLogDao,
    private val scope: CoroutineScope,
) : WidgetStateEventLogger {
    override fun log(tag: String, message: String) {
        scope.launch {
            try {
                appLogDao.log(tag, message)
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to persist state event tag=$tag", exception)
            }
        }
    }

    private companion object {
        const val TAG = "WidgetStateEventLogger"
    }
}
