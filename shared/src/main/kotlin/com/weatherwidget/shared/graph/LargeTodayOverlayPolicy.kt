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
        todayVisible: Boolean,
        extraHistoryColumns: Int = 0,
    ): Decision {
        val enabled =
            useGraph &&
                todayVisible &&
                extraHistoryColumns == 0 &&
                availableColumns >= profile.minColumns &&
                rows >= MIN_ROWS
        return Decision(
            enabled = enabled,
            displayColumns = if (enabled) availableColumns - 1 else availableColumns,
        )
    }
}
