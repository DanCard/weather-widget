package com.weatherwidget.shared.graph

/** Platform-neutral eligibility and column-count policy for the detailed Today column. */
object LargeTodayOverlayPolicy {
    const val MIN_ROWS = 4
    const val TODAY_WIDTH_MULTIPLIER = 1.25f

    enum class Profile(val minColumns: Int) {
        ANDROID_WIDGET(minColumns = 10),
        DESKTOP(minColumns = 9),
    }

    data class Decision(
        val enabled: Boolean,
        val displayColumns: Int,
    )

    fun resolve(
        profile: Profile,
        availableColumns: Int,
        rows: Int,
        useGraph: Boolean,
        /**
         * Whether today falls inside the window the caller actually RENDERS. Callers that widen the
         * rendered window (desktop's zoom-out `dailyExtraHistory` columns) must account for that
         * before calling: this policy has no view of the date range. A previously-accepted
         * `extraHistoryColumns` parameter was `@Suppress("unused")` here, which read as if the
         * policy handled it and hid a bug where the desktop overlay switched itself off while today
         * was plainly on screen.
         */
        todayVisible: Boolean,
    ): Decision {
        val enabled =
            useGraph &&
                todayVisible &&
                availableColumns >= profile.minColumns &&
                rows >= MIN_ROWS
        return Decision(
            enabled = enabled,
            displayColumns = if (enabled) availableColumns - 1 else availableColumns,
        )
    }
}
