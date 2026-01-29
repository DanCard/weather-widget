package com.weatherwidget.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WeatherEntity::class, ForecastSnapshotEntity::class],
    version = 4,
    exportSchema = false
)
abstract class WeatherDatabase : RoomDatabase() {
    abstract fun weatherDao(): WeatherDao
    abstract fun forecastSnapshotDao(): ForecastSnapshotDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
    }
}
