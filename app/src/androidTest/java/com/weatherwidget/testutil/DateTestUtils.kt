package com.weatherwidget.testutil

import com.weatherwidget.widget.WidgetConstants
import java.time.LocalDate

/** Converts a "YYYY-MM-DD" string to UTC midnight epoch millis, matching the DB convention. */
fun dateEpoch(dateStr: String): Long = LocalDate.parse(dateStr).toEpochDay() * WidgetConstants.MS_IN_A_DAY
