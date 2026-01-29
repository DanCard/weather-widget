package com.weatherwidget.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WeatherEntity::class, ForecastSnapshotEntity::class],
    version = 2,
    exportSchema = false
)
abstract class WeatherDatabase : RoomDatabase() {
    abstract fun weatherDao(): WeatherDao
    abstract fun forecastSnapshotDao(): ForecastSnapshotDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS forecast_snapshots (
                        targetDate TEXT NOT NULL,
                        forecastDate TEXT NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        highTemp INTEGER NOT NULL,
                        lowTemp INTEGER NOT NULL,
                        `condition` TEXT NOT NULL,
                        source TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY(targetDate, forecastDate, locationLat, locationLon)
                    )
                """.trimIndent())
            }
        }
    }
}
