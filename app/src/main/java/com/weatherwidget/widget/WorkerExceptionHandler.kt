package com.weatherwidget.widget

import androidx.work.ListenableWorker
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import kotlinx.coroutines.CancellationException

internal suspend inline fun handleWorkerExceptions(
    appLogDao: AppLogDao,
    cancellationTag: String,
    cancellationMessage: String,
    errorTag: String,
    errorMessage: String,
    onException: (Exception) -> ListenableWorker.Result,
    block: () -> ListenableWorker.Result,
): ListenableWorker.Result = try {
    block()
} catch (e: CancellationException) {
    appLogDao.log(cancellationTag, cancellationMessage, "INFO")
    throw e
} catch (e: Exception) {
    appLogDao.logException(errorTag, errorMessage, e)
    onException(e)
}
