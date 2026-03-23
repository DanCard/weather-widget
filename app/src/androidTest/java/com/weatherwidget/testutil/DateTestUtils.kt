package com.weatherwidget.testutil

import java.time.LocalDate

/** Converts a "YYYY-MM-DD" string to UTC midnight epoch millis, matching the DB convention. */
fun dateEpoch(dateStr: String): Long = LocalDate.parse(dateStr).toEpochDay() * 86400_000L
