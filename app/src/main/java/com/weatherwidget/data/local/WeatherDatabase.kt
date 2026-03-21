package com.weatherwidget.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.JournalMode
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ForecastEntity::class, HourlyForecastEntity::class, AppLogEntity::class, ClimateNormalEntity::class, ObservationEntity::class, ApiUsageEntity::class, DailyExtremeEntity::class],
    version = 39,
    exportSchema = true,
)
abstract class WeatherDatabase : RoomDatabase() {
    abstract fun forecastDao(): ForecastDao

    abstract fun hourlyForecastDao(): HourlyForecastDao

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
                        .fallbackToDestructiveMigration()
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
