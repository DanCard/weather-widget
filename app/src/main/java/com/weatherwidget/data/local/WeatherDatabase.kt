package com.weatherwidget.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.JournalMode
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ForecastEntity::class, HourlyForecastEntity::class, HourlyForecastHistoryEntity::class, AppLogEntity::class, ClimateNormalEntity::class, ObservationEntity::class, ApiUsageEntity::class, DailyHistoryEntity::class],
    version = 54,
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

    abstract fun dailyHistoryDao(): DailyHistoryDao

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
                db.execSQL("ALTER TABLE `observations` ADD COLUMN `precipAmountMm` REAL")
                db.execSQL("ALTER TABLE `daily_extremes` ADD COLUMN `precipAmountMm` REAL")
            }
        }

        /**
         * Adds precipitation columns to observations and daily_extremes tables (renamed to
         * daily_history in MIGRATION_50_51 — this migration predates the rename and must keep
         * operating on the table's actual name at the time, `daily_extremes`).
         * Handles databases created at v45 with or without precipAmountMm (the column was
         * added mid-v45 lifecycle). Uses conditional ALTER to be idempotent.
         */
        val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "observations", "precipAmountMm", "REAL")
                addColumnIfMissing(db, "daily_extremes", "precipAmountMm", "REAL")
                addColumnIfMissing(db, "daily_extremes", "precipDayMm", "REAL")
                addColumnIfMissing(db, "daily_extremes", "precipNightMm", "REAL")
            }
        }

        // climate_normals high/low temps changed from Int to Float (one-tenth precision).
        // SQLite can't ALTER a column's affinity, and the cached values are disposable
        // (and were wrong — sourced from a single climate-model year), so recreate the
        // table with REAL columns. This also wipes the stale normals so they refetch.
        val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `climate_normals`")
                db.execSQL(
                    "CREATE TABLE `climate_normals` (`monthDay` TEXT NOT NULL, " +
                        "`locationKey` TEXT NOT NULL, `highTemp` REAL NOT NULL, " +
                        "`lowTemp` REAL NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`monthDay`, `locationKey`))",
                )
            }
        }

        // Data-only cleanup (table structure is unchanged from v47). Float lat/lon in the PK let
        // GPS/geocoding jitter accumulate one row per precision for the same hour; this collapses
        // those fragments — keeping the freshest per quantized key — and rounds the surviving rows
        // onto the same grid LocationMatch.quantize writes to going forward, so REPLACE overwrites
        // cleanly. No schema change, so 48.json is structurally identical to 47.json.
        val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // hourly_forecasts: dedupe per (dateTime, source, rounded lat/lon), keep freshest.
                db.execSQL(
                    "DELETE FROM `hourly_forecasts` WHERE rowid NOT IN (" +
                        "SELECT rowid FROM `hourly_forecasts` h WHERE fetchedAt = (" +
                        "SELECT MAX(fetchedAt) FROM `hourly_forecasts` h2 WHERE " +
                        "h2.dateTime = h.dateTime AND h2.source = h.source AND " +
                        "ROUND(h2.locationLat, 3) = ROUND(h.locationLat, 3) AND " +
                        "ROUND(h2.locationLon, 3) = ROUND(h.locationLon, 3)) " +
                        "GROUP BY h.dateTime, h.source, ROUND(h.locationLat, 3), ROUND(h.locationLon, 3))",
                )
                db.execSQL("UPDATE `hourly_forecasts` SET `locationLat` = ROUND(`locationLat`, 3), `locationLon` = ROUND(`locationLon`, 3)")

                // hourly_forecast_history: same, but keyed also by snapshotBucket (each bucket is a
                // distinct as-predicted generation we want to preserve).
                db.execSQL(
                    "DELETE FROM `hourly_forecast_history` WHERE rowid NOT IN (" +
                        "SELECT rowid FROM `hourly_forecast_history` h WHERE fetchedAt = (" +
                        "SELECT MAX(fetchedAt) FROM `hourly_forecast_history` h2 WHERE " +
                        "h2.dateTime = h.dateTime AND h2.source = h.source AND h2.snapshotBucket = h.snapshotBucket AND " +
                        "ROUND(h2.locationLat, 3) = ROUND(h.locationLat, 3) AND " +
                        "ROUND(h2.locationLon, 3) = ROUND(h.locationLon, 3)) " +
                        "GROUP BY h.dateTime, h.source, h.snapshotBucket, ROUND(h.locationLat, 3), ROUND(h.locationLon, 3))",
                )
                db.execSQL("UPDATE `hourly_forecast_history` SET `locationLat` = ROUND(`locationLat`, 3), `locationLon` = ROUND(`locationLon`, 3)")
            }
        }

        val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `observations` ADD COLUMN `isWebFallback` INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Data-only cleanup: rounds `forecasts` lat/lon onto the LocationMatch.quantize grid, the
        // same treatment MIGRATION_47_48 gave the hourly tables. A fetch-coordinate jitter hop
        // (~1.5 m) strands the old exact-key site with its last batch, which the daily view can
        // then show days-stale. Unlike the hourly collapse we must NOT keep only the freshest row
        // per key — `forecasts` intentionally holds one row per batch for accuracy/evolution
        // history — so only rows colliding on the full rounded PK are deduped (fetchedAt is
        // ms-precision, so genuine collisions are vanishingly rare). No schema change.
        val MIGRATION_49_50 = object : Migration(49, 50) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM `forecasts` WHERE rowid NOT IN (" +
                        "SELECT MAX(rowid) FROM `forecasts` GROUP BY targetDate, forecastDate, source, fetchedAt, " +
                        "ROUND(locationLat, 3), ROUND(locationLon, 3))",
                )
                db.execSQL("UPDATE `forecasts` SET `locationLat` = ROUND(`locationLat`, 3), `locationLon` = ROUND(`locationLon`, 3)")
            }
        }

        // Renames daily_extremes -> daily_history (the table now also holds displayed forecast
        // rain-chance snapshots, not just temperature extremes) and adds the two chance columns.
        // SQLite's ALTER TABLE RENAME does not rename dependent indices, so the old
        // index_daily_extremes_* index must be dropped and recreated under Room's expected name
        // for the new table name or schema validation will fail post-migration.
        val MIGRATION_50_51 = object : Migration(50, 51) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `daily_extremes` RENAME TO `daily_history`")
                db.execSQL("DROP INDEX IF EXISTS `index_daily_extremes_date_locationLat_locationLon`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_daily_history_date_locationLat_locationLon` " +
                        "ON `daily_history` (`date`, `locationLat`, `locationLon`)",
                )
                db.execSQL("ALTER TABLE `daily_history` ADD COLUMN `forecastDayPrecipChance` INTEGER")
                db.execSQL("ALTER TABLE `daily_history` ADD COLUMN `forecastNightPrecipChance` INTEGER")
            }
        }

        // Frozen forecast-overlay + noon-cloud columns (see DailyHistoryFreeze): the daily bar
        // view's remaining external display inputs, archived per day so past days render from
        // daily_history alone once the forecasts / hourly tables age out.
        val MIGRATION_51_52 = object : Migration(51, 52) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `daily_history` ADD COLUMN `forecastHighTemp` REAL")
                db.execSQL("ALTER TABLE `daily_history` ADD COLUMN `forecastLowTemp` REAL")
                db.execSQL("ALTER TABLE `daily_history` ADD COLUMN `forecastPrecipAmountMm` REAL")
                db.execSQL("ALTER TABLE `daily_history` ADD COLUMN `noonCloudPercent` INTEGER")
            }
        }

        val MIGRATION_52_53 = object : Migration(52, 53) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_hourly_forecast_history_locationLat_locationLon_source_snapshotBucket`")
                db.execSQL("ALTER TABLE `hourly_forecast_history` RENAME COLUMN `snapshotBucket` TO `timestampToGroupPredictions`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_hourly_forecast_history_locationLat_locationLon_source_timestampToGroupPredictions` " +
                        "ON `hourly_forecast_history` (`locationLat`, `locationLon`, `source`, `timestampToGroupPredictions`)",
                )
            }
        }

        val MIGRATION_53_54 = object : Migration(53, 54) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_forecasts_targetDate_source_locationLat_locationLon_batchFetchedAt`")
                db.execSQL("ALTER TABLE `forecasts` RENAME COLUMN `forecastDate` TO `dateOfPrediction`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_forecasts_targetDate_source_locationLat_locationLon_batchFetchedAt` " +
                        "ON `forecasts` (`targetDate`, `source`, `locationLat`, `locationLon`, `batchFetchedAt`)",
                )
            }
        }

        private fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String, type: String) {
            val cursor = db.query("PRAGMA table_info($table)")
            val columns = mutableListOf<String>()
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            cursor.close()
            if (!columns.contains(column)) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $type")
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

                // Self-healing: if a previous destructive migration set the
                // database version to 46 but the schema is actually v45
                // (daily_extremes missing precipDayMm), reset version to 45
                // so Room will run MIGRATION_45_46 properly.
                healCorruptDatabaseVersion(context, dbName!!)

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
                        .addMigrations(MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48, MIGRATION_48_49, MIGRATION_49_50, MIGRATION_50_51, MIGRATION_51_52, MIGRATION_52_53, MIGRATION_53_54)
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

        /**
         * Fixes corrupt database states that prevent Room migrations from running:
         *
         * 1. Version=46 but missing precipDayMm (from a previous destructive migration that
         *    set version to 46 without actually adding the columns). Resets to v45 so
         *    MIGRATION_45_46 will run.
         *
         * 2. Version=45 but missing precipAmountMm on observations (from old v45 code before
         *    commit 70e2241 added the column). Resets to v44 so MIGRATION_44_45 will add it.
         */
        private fun healCorruptDatabaseVersion(context: Context, dbName: String) {
            try {
                val dbPath = context.getDatabasePath(dbName)
                if (!dbPath.exists()) return

                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbPath.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
                )
                try {
                    val version = db.version
                    if (version == 46) {
                        val columns = getTableColumns(db, "daily_extremes")
                        if (!columns.contains("precipDayMm")) {
                            Log.w("WeatherDatabase", "healCorruptDatabaseVersion: DB v46 missing precipDayMm; resetting to v45")
                            db.version = 45
                        }
                    }
                    // Re-check after potential v46→v45 reset
                    if (db.version == 45) {
                        val columns = getTableColumns(db, "observations")
                        if (!columns.contains("precipAmountMm")) {
                            Log.w("WeatherDatabase", "healCorruptDatabaseVersion: DB v45 missing precipAmountMm; resetting to v44")
                            db.version = 44
                        }
                    }
                } finally {
                    db.close()
                }
            } catch (e: Exception) {
                Log.e("WeatherDatabase", "healCorruptDatabaseVersion: Error", e)
            }
        }

        private fun getTableColumns(db: android.database.sqlite.SQLiteDatabase, table: String): List<String> {
            val cursor = db.rawQuery("PRAGMA table_info($table)", null)
            val columns = mutableListOf<String>()
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            cursor.close()
            return columns
        }

    }
}
