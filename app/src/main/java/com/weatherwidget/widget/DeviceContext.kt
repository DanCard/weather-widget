package com.weatherwidget.widget

internal data class DeviceContext(
    val isCharging: Boolean,
    val batteryLevel: Int,
    val isScreenInteractive: Boolean,
    val lastFullFetchAgeSeconds: Long,
    /** The next alarm, else the learned unplug time ([PreWakeFetchScheduler.nextWake]); null when neither. */
    val wakeAtMs: Long? = null,
)
