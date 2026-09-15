package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.weatherwidget.desktop.theme.WeatherDarkColorScheme
import com.weatherwidget.desktop.theme.WeatherTypography
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
    val windowState = rememberSanitizedWindowState(
        savedX = null,
        savedY = null,
        savedWidth = null,
        savedHeight = null,
        defaultWidth = 560.dp,
        defaultHeight = 680.dp,
        defaultAlignment = Alignment.Center,
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

/**
 * Desktop counterpart of Android's `IconGalleryActivity`: Settings → "View Icon Gallery" opens the
 * grid in its own window instead of inlining it in the form.
 */
@Composable
internal fun IconGalleryWindowHost(
    icon: Painter,
    onClose: () -> Unit,
) {
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        width = 520.dp,
        height = 420.dp,
    )
    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = "Icon Gallery",
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
        MaterialTheme(colorScheme = WeatherDarkColorScheme, typography = WeatherTypography) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Comprehensive gallery of all weather icons used in the widget.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    IconGallery(iconSize = 56.dp, cellWidth = 110.dp)
                }
            }
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
    onOpenIconGallery: () -> Unit,
    onRefreshData: () -> Unit,
    onViewAppLogs: () -> Unit,
) {
    val latestConfig = rememberUpdatedState(config)
    var settingsDraft by remember { mutableStateOf<DesktopConfig?>(null) }
    val windowState = rememberSanitizedWindowState(
        savedX = config.settingsWindowX,
        savedY = config.settingsWindowY,
        savedWidth = config.settingsWindowWidth,
        savedHeight = config.settingsWindowHeight,
        defaultWidth = 500.dp,
        defaultHeight = 700.dp,
        defaultAlignment = Alignment.Center,
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
        LaunchedEffect(Unit) {
            window.toFront()
            window.requestFocus()
        }
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
            onOpenIconGallery = onOpenIconGallery,
            isRefreshing = isRefreshing,
            onRefreshBreadcrumb = { message -> weatherDao.log("REFRESH_CLICK", message, "INFO") },
            onRefreshData = onRefreshData,
            onViewAppLogs = onViewAppLogs,
            locationResolver = locationResolver,
            onSubmitBugReport = { openInBrowser(buildBugReportMailto(latestConfig.value)) },
            dataUsageProvider = { weatherDao.queryNetworkUsageReport() },
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
    val windowState = rememberSanitizedWindowState(
        savedX = config.windowX,
        savedY = config.windowY,
        savedWidth = config.windowWidth,
        savedHeight = config.windowHeight,
        defaultWidth = 380.dp,
        defaultHeight = 320.dp,
        defaultAlignment = Alignment.TopEnd,
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
