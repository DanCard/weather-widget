package com.weatherwidget.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WeatherEntity::class, ForecastSnapshotEntity::class, HourlyForecastEntity::class, AppLogEntity::class],
    version = 16,
    exportSchema = true
)
abstract class WeatherDatabase : RoomDatabase() {
    abstract fun weatherDao(): WeatherDao
    abstract fun forecastSnapshotDao(): ForecastSnapshotDao
    abstract fun hourlyForecastDao(): HourlyForecastDao
    abstract fun appLogDao(): AppLogDao

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
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                        MIGRATION_12_14, MIGRATION_13_14, MIGRATION_14_15,
                        MIGRATION_15_16
                    )
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            db.execSQL(
                                "INSERT INTO app_logs (timestamp, tag, message, level) VALUES (?, ?, ?, ?)",
                                arrayOf(System.currentTimeMillis(), "DB_CREATE", "Database created from scratch", "INFO")
                            )
                        }

                        override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                            super.onDestructiveMigration(db)
                            db.execSQL(
                                "INSERT INTO app_logs (timestamp, tag, message, level) VALUES (?, ?, ?, ?)",
                                arrayOf(System.currentTimeMillis(), "DB_DESTRUCTIVE_MIGRATION", "Database wiped due to missing migration path", "WARN")
                            )
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            // Optional: Log every time the DB is opened to track app restarts
                            db.execSQL(
                                "INSERT INTO app_logs (timestamp, tag, message, level) VALUES (?, ?, ?, ?)",
                                arrayOf(System.currentTimeMillis(), "DB_OPEN", "Database opened", "INFO")
                            )
                        }
                    })
                    .fallbackToDestructiveMigration()
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

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Convert hourly_forecasts temperature column from INTEGER to REAL for decimal precision
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS hourly_forecasts_new (
                        dateTime TEXT NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        temperature REAL NOT NULL,
                        source TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY(dateTime, source, locationLat, locationLon)
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO hourly_forecasts_new
                    SELECT dateTime, locationLat, locationLon,
                           CAST(temperature AS REAL), source, fetchedAt
                    FROM hourly_forecasts
                """.trimIndent())

                db.execSQL("DROP TABLE hourly_forecasts")
                db.execSQL("ALTER TABLE hourly_forecasts_new RENAME TO hourly_forecasts")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add stationId column to track which NWS observation station provided the data
                db.execSQL("ALTER TABLE weather_data ADD COLUMN stationId TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Migrate weather_data to support nullable temperatures
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS weather_data_v9 (
                        date TEXT NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        locationName TEXT NOT NULL,
                        highTemp INTEGER,
                        lowTemp INTEGER,
                        currentTemp INTEGER,
                        `condition` TEXT NOT NULL,
                        isActual INTEGER NOT NULL,
                        source TEXT NOT NULL DEFAULT 'Unknown',
                        stationId TEXT DEFAULT NULL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY(date, source)
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO weather_data_v9 (
                        date, locationLat, locationLon, locationName, 
                        highTemp, lowTemp, currentTemp, `condition`, 
                        isActual, source, stationId, fetchedAt
                    )
                    SELECT 
                        date, locationLat, locationLon, locationName, 
                        highTemp, lowTemp, currentTemp, `condition`, 
                        isActual, source, stationId, fetchedAt 
                    FROM weather_data
                """.trimIndent())

                db.execSQL("DROP TABLE weather_data")
                db.execSQL("ALTER TABLE weather_data_v9 RENAME TO weather_data")

                // 2. Migrate forecast_snapshots to support nullable temperatures
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS forecast_snapshots_v9 (
                        targetDate TEXT NOT NULL,
                        forecastDate TEXT NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        highTemp INTEGER,
                        lowTemp INTEGER,
                        `condition` TEXT NOT NULL,
                        source TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY(targetDate, forecastDate, locationLat, locationLon, source)
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO forecast_snapshots_v9 (
                        targetDate, forecastDate, locationLat, locationLon, 
                        highTemp, lowTemp, `condition`, source, fetchedAt
                    )
                    SELECT 
                        targetDate, forecastDate, locationLat, locationLon, 
                        highTemp, lowTemp, `condition`, source, fetchedAt 
                    FROM forecast_snapshots
                """.trimIndent())

                db.execSQL("DROP TABLE forecast_snapshots")
                db.execSQL("ALTER TABLE forecast_snapshots_v9 RENAME TO forecast_snapshots")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE weather_data ADD COLUMN isClimateNormal INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add condition column to hourly_forecasts
                db.execSQL("ALTER TABLE hourly_forecasts ADD COLUMN condition TEXT NOT NULL DEFAULT 'Unknown'")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add fetchedAt to primary key to allow multiple snapshots per fetch
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS forecast_snapshots_v12 (
                        targetDate TEXT NOT NULL,
                        forecastDate TEXT NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        highTemp INTEGER,
                        lowTemp INTEGER,
                        `condition` TEXT NOT NULL,
                        source TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY(targetDate, forecastDate, locationLat, locationLon, source, fetchedAt)
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO forecast_snapshots_v12 (
                        targetDate, forecastDate, locationLat, locationLon, 
                        highTemp, lowTemp, `condition`, source, fetchedAt
                    )
                    SELECT 
                        targetDate, forecastDate, locationLat, locationLon, 
                        highTemp, lowTemp, `condition`, source, fetchedAt 
                    FROM forecast_snapshots
                """.trimIndent())

                db.execSQL("DROP TABLE forecast_snapshots")
                db.execSQL("ALTER TABLE forecast_snapshots_v12 RENAME TO forecast_snapshots")
            }
        }

        val MIGRATION_12_14 = object : Migration(12, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 13 introduced app_logs table; this migration jumps 12→14 so we must create it here
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS app_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        tag TEXT NOT NULL,
                        message TEXT NOT NULL,
                        level TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_weather_data_locationLat_locationLon ON weather_data (locationLat, locationLon)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_forecast_snapshots_locationLat_locationLon ON forecast_snapshots (locationLat, locationLon)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_hourly_forecasts_locationLat_locationLon ON hourly_forecasts (locationLat, locationLon)")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_weather_data_locationLat_locationLon ON weather_data (locationLat, locationLon)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_forecast_snapshots_locationLat_locationLon ON forecast_snapshots (locationLat, locationLon)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_hourly_forecasts_locationLat_locationLon ON hourly_forecasts (locationLat, locationLon)")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE weather_data ADD COLUMN precipProbability INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE hourly_forecasts ADD COLUMN precipProbability INTEGER DEFAULT NULL")
            }
        }
    }
}
