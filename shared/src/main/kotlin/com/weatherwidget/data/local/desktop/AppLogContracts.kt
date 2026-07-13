package com.weatherwidget.data.local.desktop

/**
 * app_logs rows come in two kinds: diagnostics (free to reword, demote, or delete) and
 * *contracts* — rows that code reads back and parses, often across the daemon/UI process
 * boundary. Everything in this file is a contract: writers and readers must both go through
 * these helpers so the format cannot drift on one side only. AppLogsContractTest round-trips
 * them through a real database.
 */

/**
 * Wake/network transition marker behind [DesktopWeatherDao.getLatestWakeEventMs]. Written by the
 * daemon at exactly three transitions (resume kick accepted, network-restored kick accepted,
 * daemon startup); read by the UI to blame fresh offline fetch failures on post-wake network
 * warm-up instead of surfacing a hard error. Deliberately separate from the diagnostic
 * RESUME_DETECT/NETWORK_DETECT rows.
 */
object WakeEventLog {
    const val TAG = "WAKE_EVENT"

    fun message(reason: String): String = "reason=$reason"
}

/**
 * The CURRENT_TEMP_STATUS message format — current-temp fetch health. Readers depend on:
 * the `source=<id> ` prefix ([DesktopWeatherDao.getLatestCurrentTempStatus]'s LIKE filter),
 * the literal `ok=true`/`ok=false` token ([isOk]), and on failures the `class=`/`detail=`
 * fields (the desktop UI classifies post-wake offline failures via
 * [com.weatherwidget.data.model.isOfflineExceptionName]).
 */
object CurrentTempStatusLog {
    const val TAG = "CURRENT_TEMP_STATUS"

    fun ok(sourceId: String): String = "source=$sourceId ok=true"

    fun failure(sourceId: String, e: Throwable): String =
        "source=$sourceId ok=false class=${e::class.simpleName} detail=${e.message}"

    fun isOk(message: String): Boolean = message.contains("ok=true")

    /** The exception class name from a [failure] message ("" when absent). */
    fun parseFailureClassName(message: String): String =
        message.substringAfter("class=", "").substringBefore(" detail=")

    /** The detail portion of a [failure] message ("" when absent). */
    fun parseFailureDetail(message: String): String =
        message.substringAfter("detail=", "")
}
