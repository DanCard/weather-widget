package com.weatherwidget.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WeatherEntity::class, ForecastSnapshotEntity::class, HourlyForecastEntity::class],
    version = 6,
    exportSchema = false
)
abstract class WeatherDatabase : RoomDatabase() {
    abstract fun weatherDao(): WeatherDao
    abstract fun forecastSnapshotDao(): ForecastSnapshotDao
    abstract fun hourlyForecastDao(): HourlyForecastDao

    companion object {
        @Volatile
        private var INSTANCE: WeatherDatabase? = null

        fun getDatabase(context: Context): WeatherDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WeatherDatabase::class.java,
                    "weather_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                INSTANCE = instance
                instance
            }
        }
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE weather_data ADD COLUMN source TEXT NOT NULL DEFAULT 'Unknown'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Change primary key from (date) to (date, source) to store both APIs' data
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS weather_data_new (
                        date TEXT NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        locationName TEXT NOT NULL,
                        highTemp INTEGER NOT NULL,
                        lowTemp INTEGER NOT NULL,
                        currentTemp INTEGER,
                        `condition` TEXT NOT NULL,
                        isActual INTEGER NOT NULL,
                        source TEXT NOT NULL DEFAULT 'Unknown',
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY(date, source)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO weather_data_new (date, locationLat, locationLon, locationName, highTemp, lowTemp, currentTemp, `condition`, isActual, source, fetchedAt)
                    SELECT date, locationLat, locationLon, locationName, highTemp, lowTemp, currentTemp, `condition`, isActual, source, fetchedAt FROM weather_data
                """.trimIndent())
                db.execSQL("DROP TABLE weather_data")
                db.execSQL("ALTER TABLE weather_data_new RENAME TO weather_data")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add source to primary key of forecast_snapshots to store both APIs' forecasts
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS forecast_snapshots_new (
                        targetDate TEXT NOT NULL,
                        forecastDate TEXT NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        highTemp INTEGER NOT NULL,
                        lowTemp INTEGER NOT NULL,
                        `condition` TEXT NOT NULL,
                        source TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY(targetDate, forecastDate, locationLat, locationLon, source)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO forecast_snapshots_new (targetDate, forecastDate, locationLat, locationLon, highTemp, lowTemp, `condition`, source, fetchedAt)
                    SELECT targetDate, forecastDate, locationLat, locationLon, highTemp, lowTemp, `condition`, source, fetchedAt FROM forecast_snapshots
                """.trimIndent())
                db.execSQL("DROP TABLE forecast_snapshots")
                db.execSQL("ALTER TABLE forecast_snapshots_new RENAME TO forecast_snapshots")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add hourly_forecasts table for temperature interpolation
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS hourly_forecasts (
                        dateTime TEXT NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        temperature INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY(dateTime, source, locationLat, locationLon)
                    )
                """.trimIndent())
            }
        }
    }
}
