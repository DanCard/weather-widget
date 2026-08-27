package com.weatherwidget.data.local

import androidx.room.TypeConverter
import com.weatherwidget.data.model.CloudVerticalKind

/** Stable integer persistence for [CloudVerticalKind]; unknown future codes remain readable. */
class CloudVerticalKindConverters {
    @TypeConverter
    fun toDbCode(kind: CloudVerticalKind): Int = kind.dbCode

    @TypeConverter
    fun fromDbCode(dbCode: Int): CloudVerticalKind = CloudVerticalKind.fromDbCode(dbCode)
}
