package com.weatherwidget.widget

internal data class DeviceContext(
    val isCharging: Boolean,
    val batteryLevel: Int,
    val isScreenInteractive: Boolean,
    val lastFullFetchAgeSeconds: Long,
)
