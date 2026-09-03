package com.weatherwidget.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.DataStatus
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.repository.SharedLocationResolver
import com.weatherwidget.shared.util.Log
import java.time.LocalDate

private const val TAG = "Main"

@Composable
internal fun LocationPickerWindowHost(
    locationResolver: LocationResolver,
    isFirstLaunch: Boolean,
    icon: Painter,
    onClose: () -> Unit,
    onResolved: (DesktopConfig) -> Unit,
) {
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        width = 560.dp,
        height = 680.dp,
    )
    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = "Set Weather Location",
        icon = icon,
        onKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                onClose()
                true
            } else {
                false
            }
        },
    ) {
        LocationPicker(locationResolver, allowAutoSelect = isFirstLaunch) { resolved ->
            onResolved(resolved.toConfig())
        }
    }
}

@Composable
internal fun SettingsWindowHost(
    config: DesktopConfig,
    icon: Painter,
    isRefreshing: Boolean,
    weatherDao: DesktopWeatherDao,
    locationResolver: SharedLocationResolver,
    onSaveConfig: (DesktopConfig, String) -> Unit,
    onClose: () -> Unit,
    onExit: () -> Unit,
    onUpdateLocation: () -> Unit,
    onOpenObservations: () -> Unit,
    onRefreshData: () -> Unit,
    onViewAppLogs: () -> Unit,
) {
    val latestConfig = rememberUpdatedState(config)
    var settingsDraft by remember { mutableStateOf<DesktopConfig?>(null) }
    val windowState = rememberWindowState(
        position = if (config.settingsWindowX != null && config.settingsWindowY != null) {
            WindowPosition(config.settingsWindowX.dp, config.settingsWindowY.dp)
        } else {
            WindowPosition(Alignment.Center)
        },
        width = config.settingsWindowWidth?.dp ?: 500.dp,
        height = config.settingsWindowHeight?.dp ?: 700.dp,
    )

    fun closeSettings() {
        flushSettingsDraft(latestConfig.value, settingsDraft) { onSaveConfig(it, "settings-close") }
        settingsDraft = null
        onClose()
    }

    LaunchedEffect(windowState.position, windowState.size) {
        kotlinx.coroutines.delay(1000)
        val current = latestConfig.value
        val position = windowState.position
        if (position is WindowPosition.Absolute) {
            val resized = current.copy(
                settingsWindowX = position.x.value,
                settingsWindowY = position.y.value,
                settingsWindowWidth = windowState.size.width.value,
                settingsWindowHeight = windowState.size.height.value,
            )
            if (resized != current) onSaveConfig(resized, "settings-window-geometry")
        }
    }

    Window(
        onCloseRequest = ::closeSettings,
        state = windowState,
        title = "Weather Settings",
        icon = icon,
        onKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                closeSettings()
                true
            } else {
                false
            }
        },
    ) {
        SettingsWindow(
            config = config,
            onClose = ::closeSettings,
            onSave = { updated ->
                onSaveConfig(updated, "settings")
                settingsDraft = null
            },
            onDraftChanged = { draft -> settingsDraft = draft.takeIf { it != latestConfig.value } },
            onExit = onExit,
            onUpdateLocation = onUpdateLocation,
            onOpenObservations = onOpenObservations,
            isRefreshing = isRefreshing,
            onRefreshBreadcrumb = { message -> weatherDao.log("REFRESH_CLICK", message, "INFO") },
            onRefreshData = onRefreshData,
            onViewAppLogs = onViewAppLogs,
            locationResolver = locationResolver,
            onSubmitBugReport = { openInBrowser(buildBugReportMailto(latestConfig.value)) },
        )
    }
}

@Composable
internal fun PopupWindowHost(
    config: DesktopConfig,
    forecast: ForecastSnapshot?,
    dataStatus: DataStatus,
    resolvedCurrentTemp: Float?,
    resolvedDeltaFromYesterday: Float?,
    showRequestId: Int,
    icon: Painter,
    onClose: () -> Unit,
    onUpdateLocation: () -> Unit,
    onUpdateConfig: (DesktopConfig) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenObservations: () -> Unit,
    onOpenHistory: (LocalDate) -> Unit,
    onNeedHistory: (Int) -> Unit,
    onNeedHourlyRefresh: ((List<HourlyForecast>) -> Unit) -> Unit,
    onDayClickAudit: (String) -> Unit,
    historyFetchToast: String?,
    currentTempFetchError: String?,
    currentTempFetchIsWarmup: Boolean,
    onDismissCurrentTempError: () -> Unit,
) {
    val latestConfig = rememberUpdatedState(config)
    var arrowKeyHandler by remember { mutableStateOf<((left: Boolean) -> Boolean)?>(null) }
    val windowState = rememberWindowState(
        position = if (config.windowX != null && config.windowY != null) {
            WindowPosition(config.windowX.dp, config.windowY.dp)
        } else {
            WindowPosition(Alignment.TopEnd)
        },
        width = config.windowWidth?.dp ?: 380.dp,
        height = config.windowHeight?.dp ?: 320.dp,
    )

    LaunchedEffect(windowState.position, windowState.size) {
        kotlinx.coroutines.delay(1000)
        val current = latestConfig.value
        val position = windowState.position
        if (position is WindowPosition.Absolute) {
            val resized = current.copy(
                windowX = position.x.value,
                windowY = position.y.value,
                windowWidth = windowState.size.width.value,
                windowHeight = windowState.size.height.value,
            )
            if (resized != current) onUpdateConfig(resized)
        }
    }

    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = "Weather Widget",
        icon = icon,
        onKeyEvent = { keyEvent ->
            if (keyEvent.type != KeyEventType.KeyDown) {
                false
            } else {
                when (keyEvent.key) {
                    Key.Escape -> {
                        onClose()
                        true
                    }
                    Key.DirectionLeft -> arrowKeyHandler?.invoke(true) ?: false
                    Key.DirectionRight -> arrowKeyHandler?.invoke(false) ?: false
                    else -> false
                }
            }
        },
    ) {
        LaunchedEffect(Unit) { Log.i(TAG, "Window composed/visible now") }
        LaunchedEffect(showRequestId) {
            Log.i(TAG, "Window show request received: showRequestId=$showRequestId")
            if (windowState.isMinimized) windowState.isMinimized = false
            val frameState = window.extendedState
            if ((frameState and java.awt.Frame.ICONIFIED) != 0) {
                window.extendedState = java.awt.Frame.NORMAL
            }
            window.toFront()
            window.requestFocus()
        }
        WidgetPopup(
            config = config,
            forecast = forecast,
            dataStatus = dataStatus,
            resolvedCurrentTemp = resolvedCurrentTemp,
            resolvedDeltaFromYesterday = resolvedDeltaFromYesterday,
            onUpdateLocation = onUpdateLocation,
            onUpdateConfig = onUpdateConfig,
            onOpenSettings = onOpenSettings,
            onOpenObservations = onOpenObservations,
            onOpenHistory = onOpenHistory,
            onRegisterArrowKeyHandler = { arrowKeyHandler = it },
            onNeedHistory = onNeedHistory,
            onNeedHourlyRefresh = onNeedHourlyRefresh,
            onDayClickAudit = onDayClickAudit,
            historyFetchToast = historyFetchToast,
            currentTempFetchError = currentTempFetchError,
            currentTempFetchIsWarmup = currentTempFetchIsWarmup,
            onDismissCurrentTempError = onDismissCurrentTempError,
        )
    }
}
