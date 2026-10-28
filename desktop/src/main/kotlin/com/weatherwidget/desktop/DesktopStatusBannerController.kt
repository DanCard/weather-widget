package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.CurrentTempStatus
import com.weatherwidget.data.local.desktop.CurrentTempStatusLog
import com.weatherwidget.data.local.desktop.DesktopQuotaState
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.isOfflineExceptionName
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.GoogleQuota
import com.weatherwidget.data.remote.QuotaNotice
import com.weatherwidget.data.remote.RateLimitNotice
import com.weatherwidget.data.remote.SourceErrorDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class DesktopStatusBannerState(
    val error: String? = null,
    val pill: DesktopFailurePill? = null,
    val details: SourceErrorDetails.Content? = null,
    val isWarmup: Boolean = false,
) {
    val loggedState: String
        get() = when {
            error == null -> "none"
            isWarmup -> "warmup"
            else -> "error"
        }
}

/**
 * Manages status evaluation and automatic expiry timers for rate limits, network warmup grace,
 * and quota resets across views.
 */
internal class DesktopStatusBannerController(
    private val scope: CoroutineScope,
    private val weatherDao: DesktopWeatherDao,
    private val config: () -> DesktopConfig?,
    private val onStateChanged: (DesktopStatusBannerState) -> Unit,
    private var lastLoggedBannerState: String = "none",
    private val onLogBannerState: (String, String) -> Unit = { state, prev ->
        weatherDao.log("CURR_TEMP_BANNER", "state=$state (was $prev)", "INFO")
    },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var graceJob: Job? = null
    private var rateLimitExpiryJob: Job? = null
    private var quotaExpiryJob: Job? = null

    var currentState: DesktopStatusBannerState = DesktopStatusBannerState()
        private set

    fun cancel() {
        graceJob?.cancel()
        rateLimitExpiryJob?.cancel()
        quotaExpiryJob?.cancel()
    }

    private fun logBannerTransition(newState: DesktopStatusBannerState) {
        val stateName = newState.loggedState
        if (stateName != lastLoggedBannerState) {
            onLogBannerState(stateName, lastLoggedBannerState)
            lastLoggedBannerState = stateName
        }
    }

    private fun showDetails(
        src: String,
        sourceFailure: CurrentTempStatus?,
        now: Long,
        activeConfig: DesktopConfig,
    ): Pair<SourceErrorDetails.Content, String>? {
        val details = desktopSourceErrorDetails(
            weatherDao, src, sourceFailure, QuotaNotice.productOf(activeConfig.viewMode), now,
        ) ?: return null
        val displayName = WeatherSource.fromId(src).displayName
        val errorText = SourceErrorDetails.English.headline(displayName, details.headline) + "\n" +
            SourceErrorDetails.English.summary(details.summary)
        return details to errorText
    }

    fun updateStatus() {
        val activeConfig = config() ?: return
        val isHourly = activeConfig.viewMode.isHourly
        val src = WeatherSource.fromDisplaySource(activeConfig.displaySource).id
        val status = weatherDao.getLatestCurrentTempStatus(src)
        val statusNow = nowMs()

        rateLimitExpiryJob?.cancel()
        fun expireRateLimitBannerAfter(atMs: Long) {
            val remaining = RateLimitNotice.remainingMs(atMs, statusNow) ?: return
            rateLimitExpiryJob = scope.launch {
                delay(remaining)
                updateStatus()
            }
        }

        quotaExpiryJob?.cancel()
        fun expireQuotaBannerAfter(resetAtMs: Long) {
            val remaining = resetAtMs - statusNow
            if (remaining > 0L) {
                quotaExpiryJob = scope.launch {
                    delay(remaining)
                    updateStatus()
                }
            }
        }

        // The daily view shows a source-wide failure only when it is a quota (it says when
        // updates resume); other current-temp failures stay on the hourly graphs. A per-minute
        // 429 shows on every view, for a minute (plans/261016-quota-refusals-always-show-a-banner.md).
        val sourceFailure = status?.takeIf { !it.ok }?.takeIf {
            val kind = desktopFailureKind(it)
            when {
                kind.rateLimit -> RateLimitNotice.isShowing(it.timestamp, statusNow)
                kind.quota -> statusNow < GoogleQuota.nextResetMs(it.timestamp)
                else -> isHourly
            }
        }

        val newState: DesktopStatusBannerState
        if (status != null && sourceFailure != null) {
            val now = nowMs()
            val className = CurrentTempStatusLog.parseFailureClassName(status.message)
            val displayName = WeatherSource.fromId(src).displayName

            graceJob?.cancel()

            val wakeEventMs = weatherDao.getLatestWakeEventMs()
            if (isOfflineExceptionName(className) &&
                isNetworkWarmupWindow(wakeEventMs, now)
            ) {
                val timeRemaining = (wakeEventMs ?: 0L) + NETWORK_WARMUP_GRACE_MS - now
                if (timeRemaining > 0) {
                    graceJob = scope.launch {
                        delay(timeRemaining)
                        updateStatus()
                    }
                }
                newState = DesktopStatusBannerState(
                    error = "${displayName.uppercase()} WEATHER UPDATE\nWaiting for network to warm up…",
                    isWarmup = true,
                )
                currentState = newState
                onStateChanged(newState)
                logBannerTransition(newState)
                return
            }

            val kind = desktopFailureKind(status)
            if (kind.rateLimit) expireRateLimitBannerAfter(status.timestamp)
            if (kind.quota) expireQuotaBannerAfter(GoogleQuota.nextResetMs(status.timestamp))
            val pill = DesktopFailurePill(
                sourceLabel = displayName,
                errorCode = if (kind.rateLimit) RateLimitNotice.ERROR_CODE else kind.code,
                failureTimeMs = status.timestamp,
            )
            val detailsResult = showDetails(src, status, now, activeConfig)
            newState = DesktopStatusBannerState(
                error = detailsResult?.second,
                pill = pill,
                details = detailsResult?.first,
                isWarmup = false,
            )
        } else {
            val quotas = DesktopQuotaState(weatherDao)
            val productQuota = QuotaNotice.bannerBlock(QuotaNotice.productOf(activeConfig.viewMode)) { product ->
                quotas.productQuota(src, product, statusNow)
            }
            val partialRateLimit = if (productQuota != null) null else quotas.partialRateLimit(src, statusNow)
            val displayName = WeatherSource.fromId(src).displayName
            when {
                productQuota != null -> {
                    expireQuotaBannerAfter(productQuota.second.untilMs)
                    val pill = DesktopFailurePill(
                        sourceLabel = displayName,
                        errorCode = GoogleQuota.errorCodeFor(productQuota.first),
                        failureTimeMs = productQuota.second.sinceMs,
                    )
                    val detailsResult = showDetails(src, null, statusNow, activeConfig)
                    newState = DesktopStatusBannerState(
                        error = detailsResult?.second,
                        pill = pill,
                        details = detailsResult?.first,
                        isWarmup = false,
                    )
                }
                partialRateLimit != null -> {
                    val loggedAt = partialRateLimit.atMs
                    expireRateLimitBannerAfter(loggedAt)
                    val pill = DesktopFailurePill(
                        sourceLabel = displayName,
                        errorCode = RateLimitNotice.ERROR_CODE_PARTIAL,
                        failureTimeMs = loggedAt,
                    )
                    val detailsResult = showDetails(src, null, statusNow, activeConfig)
                    newState = DesktopStatusBannerState(
                        error = detailsResult?.second,
                        pill = pill,
                        details = detailsResult?.first,
                        isWarmup = false,
                    )
                }
                else -> {
                    newState = DesktopStatusBannerState()
                }
            }
        }
        currentState = newState
        onStateChanged(newState)
        logBannerTransition(newState)
    }
}
