package com.weatherwidget.data.local

import androidx.room.TypeConverter
import com.weatherwidget.data.model.StationType

/** Stable integer persistence for [StationType]; unknown future codes read as UNKNOWN. */
class StationTypeConverters {
    @TypeConverter
    fun toDbCode(type: StationType): Int = type.dbCode

    @TypeConverter
    fun fromDbCode(dbCode: Int): StationType = StationType.fromDbCode(dbCode)
}
