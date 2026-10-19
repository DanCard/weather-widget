package com.weatherwidget.widget

internal data class DeviceContext(
    val isCharging: Boolean,
    val batteryLevel: Int,
    val isScreenInteractive: Boolean,
    val lastFullFetchAgeSeconds: Long,
    /** The user's next alarm ([PreWakeFetchScheduler.nextAlarmMs]); null when none is set. */
    val wakeAtMs: Long? = null,
)
