package com.weatherwidget.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.JournalMode
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ForecastEntity::class, HourlyForecastEntity::class, HourlyForecastHistoryEntity::class, AppLogEntity::class, ClimateNormalEntity::class, ObservationEntity::class, ApiUsageEntity::class, DailyExtremeEntity::class],
    version = 45,
    exportSchema = true,
)
abstract class WeatherDatabase : RoomDatabase() {
    abstract fun forecastDao(): ForecastDao

    abstract fun hourlyForecastDao(): HourlyForecastDao

    abstract fun hourlyForecastHistoryDao(): HourlyForecastHistoryDao

    abstract fun appLogDao(): AppLogDao

    abstract fun climateNormalDao(): ClimateNormalDao

    abstract fun observationDao(): ObservationDao

    abstract fun apiUsageDao(): ApiUsageDao

    abstract fun dailyExtremeDao(): DailyExtremeDao

    companion object {
        @Volatile
        private var INSTANCE: WeatherDatabase? = null
        @Volatile
        private var databaseNameOverride: String? = null
        @Volatile
        private var isTesting = false

        private const val DEFAULT_DATABASE_NAME = "weather_database"
        private const val DEFAULT_TEST_DATABASE_NAME = "weather_database_test_default"

        /**
         * Adds the `hourly_forecast_history` table (hourly forecast-history snapshots) without
         * dropping existing data. The CREATE statements must match Room's generated schema for v45
         * (see app/schemas/.../45.json) exactly, or Room's post-migration validation will throw.
         */
        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hourly_forecast_history` (" +
                        "`dateTime` INTEGER NOT NULL, " +
                        "`locationLat` REAL NOT NULL, " +
                        "`locationLon` REAL NOT NULL, " +
                        "`temperature` REAL NOT NULL, " +
                        "`condition` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`snapshotBucket` INTEGER NOT NULL, " +
                        "`precipProbability` INTEGER, " +
                        "`cloudCover` INTEGER, " +
                        "`precipAmountMm` REAL, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`dateTime`, `source`, `locationLat`, `locationLon`, `snapshotBucket`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_hourly_forecast_history_locationLat_locationLon_source_snapshotBucket` " +
                        "ON `hourly_forecast_history` (`locationLat`, `locationLon`, `source`, `snapshotBucket`)",
                )
            }
        }

        fun getDatabase(context: Context): WeatherDatabase {
            return INSTANCE ?: synchronized(this) {
                // If we are in a test environment and no specific override is set,
                // use a default test database name instead of the production one.
                val dbName = if (databaseNameOverride != null) {
                    databaseNameOverride
                } else if (isTesting) {
                    DEFAULT_TEST_DATABASE_NAME
                } else {
                    DEFAULT_DATABASE_NAME
                }

                val instance =
                    Room.databaseBuilder(
                        context.applicationContext,
                        WeatherDatabase::class.java,
                        dbName!!,
                    )
                        .addCallback(
                            object : RoomDatabase.Callback() {
                                override fun onCreate(db: SupportSQLiteDatabase) {
                                    super.onCreate(db)
                                    db.execSQL(
                                        "INSERT INTO app_logs (timestamp, tag, message, level) VALUES (?, ?, ?, ?)",
                                        arrayOf<Any>(System.currentTimeMillis(), "DB_CREATE", "Database created from scratch", "INFO"),
                                    )
                                }

                                override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                                    super.onDestructiveMigration(db)
                                    db.execSQL(
                                        "INSERT INTO app_logs (timestamp, tag, message, level) VALUES (?, ?, ?, ?)",
                                        arrayOf<Any>(
                                            System.currentTimeMillis(),
                                            "DB_DESTRUCTIVE_MIGRATION",
                                            "Database wiped due to missing migration path",
                                            "WARN",
                                        ),
                                    )
                                }

                            },
                        )
                        .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                        .addMigrations(MIGRATION_44_45)
                        .fallbackToDestructiveMigration(dropAllTables = true)
                        .build()
                INSTANCE = instance
                instance
            }
        }

        @Synchronized
        fun setIsTesting(enabled: Boolean) {
            resetInstanceForTesting()
            isTesting = enabled
        }

        @Synchronized
        fun setDatabaseForTesting(db: WeatherDatabase) {
            resetInstanceForTesting()
            INSTANCE = db
            isTesting = true
        }

        @Synchronized
        fun isTestingMode(): Boolean = isTesting

        @Synchronized
        fun resetInstanceForTesting() {
            INSTANCE?.close()
            INSTANCE = null
        }

        @Synchronized
        fun setDatabaseNameOverrideForTesting(databaseName: String?) {
            resetInstanceForTesting()
            databaseNameOverride = databaseName
        }

    }
}
