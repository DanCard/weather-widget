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

/**
 * The PRODUCT_QUOTA message format — one forecast product of a source refused until a known time
 * (a per-product daily quota) while the source as a whole still updates. Written by the desktop daemon
 * when the state changes; read by the popup (a separate process) for the hourly view's banner. Readers
 * depend on the `source=<id> product=<HOURLY|DAILY> ` prefix ([DesktopWeatherDao.getLatestProductQuota]'s
 * LIKE filter), `until=<epoch ms>` (0 = cleared) and the trailing `detail=` (the 429 body).
 */
object ProductQuotaLog {
    const val TAG = "PRODUCT_QUOTA"

    fun blocked(sourceId: String, product: com.weatherwidget.data.model.ForecastProduct, untilMs: Long, detail: String): String =
        "source=$sourceId product=${product.name} until=$untilMs detail=$detail"

    fun cleared(sourceId: String, product: com.weatherwidget.data.model.ForecastProduct): String =
        "source=$sourceId product=${product.name} until=0"

    fun parseUntilMs(message: String): Long =
        message.substringAfter("until=", "0").substringBefore(' ').toLongOrNull() ?: 0L

    fun parseDetail(message: String): String = message.substringAfter("detail=", "")
}

