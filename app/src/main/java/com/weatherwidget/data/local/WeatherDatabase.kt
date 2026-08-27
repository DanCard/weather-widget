package com.weatherwidget.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.JournalMode
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ForecastEntity::class, HourlyForecastEntity::class, HourlyForecastHistoryEntity::class, AppLogEntity::class, ClimateNormalEntity::class, ObservationEntity::class, ApiUsageEntity::class, DailyHistoryEntity::class],
    version = 69,
    exportSchema = true,
)
@TypeConverters(CloudVerticalKindConverters::class)
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

        val MIGRATION_54_55 = object : Migration(54, 55) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `forecasts` DROP COLUMN `locationName`")
            }
        }

        /**
         * Marks readings rejected by upstream quality control (Synoptic qc_flags) so the stations
         * UI can show them without letting them into temperature blends.
         */
        val MIGRATION_55_56 = object : Migration(55, 56) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `observations` ADD COLUMN `qcFailed` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Makes the observation fetch site part of row identity. A station can serve multiple widget
         * locations at the same timestamp; the old (stationId, timestamp) key let the last location
         * silently replace the earlier site's coordinates, distance, and fetched-at state.
         */
        val MIGRATION_56_57 = object : Migration(56, 57) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `observations_new` (
                        `stationId` TEXT NOT NULL,
                        `stationName` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `temperature` REAL NOT NULL,
                        `condition` TEXT NOT NULL,
                        `locationLat` REAL NOT NULL,
                        `locationLon` REAL NOT NULL,
                        `distanceKm` REAL NOT NULL,
                        `stationType` TEXT NOT NULL,
                        `fetchedAt` INTEGER NOT NULL,
                        `maxTempLast24h` REAL,
                        `minTempLast24h` REAL,
                        `api` TEXT NOT NULL,
                        `precipAmountMm` REAL,
                        `isWebFallback` INTEGER NOT NULL,
                        `qcFailed` INTEGER NOT NULL,
                        PRIMARY KEY(`stationId`, `timestamp`, `locationLat`, `locationLon`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `observations_new` (
                        `stationId`, `stationName`, `timestamp`, `temperature`, `condition`,
                        `locationLat`, `locationLon`, `distanceKm`, `stationType`, `fetchedAt`,
                        `maxTempLast24h`, `minTempLast24h`, `api`, `precipAmountMm`,
                        `isWebFallback`, `qcFailed`
                    )
                    SELECT
                        `stationId`, `stationName`, `timestamp`, `temperature`, `condition`,
                        `locationLat`, `locationLon`, `distanceKm`, `stationType`, `fetchedAt`,
                        `maxTempLast24h`, `minTempLast24h`, `api`, `precipAmountMm`,
                        `isWebFallback`, `qcFailed`
                    FROM `observations`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `observations`")
                db.execSQL("ALTER TABLE `observations_new` RENAME TO `observations`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_observations_locationLat_locationLon` " +
                        "ON `observations` (`locationLat`, `locationLon`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_observations_timestamp_locationLat_locationLon` " +
                        "ON `observations` (`timestamp`, `locationLat`, `locationLon`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_observations_api` ON `observations` (`api`)",
                )
            }
        }

        /**
         * Renames highTemp/lowTemp → computedHighTemp/computedLowTemp to clarify these are the
         * IDW-blended extremes ("Location actual"), and adds apiHighTemp/apiLowTemp for
         * provider-reported observed highs/lows ("API actual"). Uses table recreation since
         * minSdk < 30 does not support ALTER TABLE RENAME COLUMN in SQLite.
         */
        val MIGRATION_57_58 = object : Migration(57, 58) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `daily_history_new` (
                        `date` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `locationLat` REAL NOT NULL,
                        `locationLon` REAL NOT NULL,
                        `computedHighTemp` REAL NOT NULL,
                        `computedLowTemp` REAL NOT NULL,
                        `condition` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `precipAmountMm` REAL,
                        `precipDayMm` REAL,
                        `precipNightMm` REAL,
                        `forecastDayPrecipChance` INTEGER,
                        `forecastNightPrecipChance` INTEGER,
                        `forecastHighTemp` REAL,
                        `forecastLowTemp` REAL,
                        `forecastPrecipAmountMm` REAL,
                        `noonCloudPercent` INTEGER,
                        `apiHighTemp` REAL,
                        `apiLowTemp` REAL,
                        PRIMARY KEY(`date`, `source`, `locationLat`, `locationLon`)
                    )
                    """.trimIndent(),
                )
                val existingColumns = mutableListOf<String>()
                db.query("PRAGMA table_info(daily_history)").use { cursor ->
                    while (cursor.moveToNext()) {
                        existingColumns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                }
                val hasHighTemp = existingColumns.contains("highTemp")
                val hasLowTemp = existingColumns.contains("lowTemp")
                // Columns already renamed (future proofing — Room re-runs migration on same version
                // after a destructive fallback); columns absent (fresh install at v57).
                val highCol = if (hasHighTemp) "highTemp" else if (existingColumns.contains("computedHighTemp")) "computedHighTemp" else null
                val lowCol = if (hasLowTemp) "lowTemp" else if (existingColumns.contains("computedLowTemp")) "computedLowTemp" else null
                if (highCol != null && lowCol != null) {
                    db.execSQL(
                        """
                        INSERT INTO `daily_history_new` (
                            `date`, `source`, `locationLat`, `locationLon`,
                            `computedHighTemp`, `computedLowTemp`, `condition`, `updatedAt`,
                            `precipAmountMm`, `precipDayMm`, `precipNightMm`,
                            `forecastDayPrecipChance`, `forecastNightPrecipChance`,
                            `forecastHighTemp`, `forecastLowTemp`,
                            `forecastPrecipAmountMm`, `noonCloudPercent`
                        )
                        SELECT
                            `date`, `source`, `locationLat`, `locationLon`,
                            `$highCol`, `$lowCol`, `condition`, `updatedAt`,
                            `precipAmountMm`, `precipDayMm`, `precipNightMm`,
                            `forecastDayPrecipChance`, `forecastNightPrecipChance`,
                            `forecastHighTemp`, `forecastLowTemp`,
                            `forecastPrecipAmountMm`, `noonCloudPercent`
                        FROM `daily_history`
                        """.trimIndent(),
                    )
                }
                db.execSQL("DROP TABLE `daily_history`")
                db.execSQL("ALTER TABLE `daily_history_new` RENAME TO `daily_history`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_daily_history_date_locationLat_locationLon` " +
                        "ON `daily_history` (`date`, `locationLat`, `locationLon`)",
                )
            }
        }

        /**
         * Adds `apiStationId`/`apiStationDistanceKm`, and **clears every stored NWS "API actual"**.
         *
         * Exactly two writers ever populated `apiHighTemp`/`apiLowTemp` for NWS, and both were
         * removed on 2026-08-08:
         *
         *  1. `persistNwsGridpointActuals` filed the leftover past-date windows of the NWS
         *     gridpoint *forecast* grid, so a past day's "actual" was that day's forecast
         *     (measured: 2026-08-05 stored 82.0 against a real 75.0), and
         *  2. `backfillNwsApiActualsFromArchive` filled the gaps with Open-Meteo's ERA5 archive —
         *     another provider's data presented as NWS's own.
         *
         * So there is no such thing as a legitimate pre-v59 NWS api actual to preserve, and a
         * value-matching heuristic would only have caught case 1. Clear the lot; the station
         * writer refills whatever the retained observations cover (~10 days). Older dates stay
         * null and drop out of the statistics, which is correct — we have no measurement for them.
         *
         * Deliberately scoped to `source = 'NWS'`: this historical migration predates the later
         * Open-Meteo Forecast API provenance correction and its one-time data cleanup.
         */
        val MIGRATION_58_59 = object : Migration(58, 59) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "daily_history", "apiStationId", "TEXT")
                addColumnIfMissing(db, "daily_history", "apiStationDistanceKm", "REAL")
                db.execSQL(
                    "UPDATE `daily_history` SET `apiHighTemp` = NULL, `apiLowTemp` = NULL " +
                        "WHERE `source` = 'NWS'",
                )
            }
        }

        /**
         * Adds `actualsSource`: which pipeline produced a row's NWS actuals (see
         * `DailyActualsSource`). Existing rows get NULL — they predate the distinction, and a null
         * source simply means "not resolved", which is the correct reading for them.
         *
         * This also replaces the implicit freeze marker. `persistExtremes` previously keyed its
         * "don't rebuild a past day's blend from the stored pool" guard on `apiStationId != null`,
         * conflating provenance with station identity.
         */
        val MIGRATION_59_60 = object : Migration(59, 60) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "daily_history", "actualsSource", "TEXT")
            }
        }

        /**
         * Adds `lastWriter` — which code path last wrote the row (see `DailyHistoryWriter`).
         * Diagnostic only; existing rows get NULL, meaning "written before this was recorded".
         */
        val MIGRATION_60_61 = object : Migration(60, 61) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "daily_history", "lastWriter", "TEXT")
            }
        }

        /**
         * Low-layer cloud cover on the live hourly rows. Added alongside the total column rather
         * than replacing it: the total is what dims the sun and drives the condition icon, while the
         * low layer is what someone outside would call cloudiness — and the two diverge hard under
         * cirrus (measured 2026-08-20: total 83-99% all afternoon, low 6-13%, surface stations
         * clear). The cloud graph draws the low layer on both curves; nothing else changes.
         */
        val MIGRATION_61_62 = object : Migration(61, 62) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "hourly_forecasts", "cloudCoverLow", "INTEGER")
                addColumnIfMissing(db, "hourly_forecast_history", "cloudCoverLow", "INTEGER")
            }
        }

        /**
         * Cloud cover on `observations`, and the retirement of the parallel `OPEN_METEO_RETRO`
         * series that v62 introduced.
         *
         * v62 filed cloud actuals as synthetic rows in `hourly_forecast_history` because
         * `observations` had no cloud column — a second mechanism for a job
         * `HistoricalActualsBackfill` already did for temperature, precip and condition. With the
         * columns present the backfill carries cloud too, so the parallel path goes away and the
         * cloud actual becomes timestamp-keyed like every other actual (which is also what lets it
         * hold sub-hourly readings).
         */
        val MIGRATION_62_63 = object : Migration(62, 63) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "observations", "cloudCover", "INTEGER")
                addColumnIfMissing(db, "observations", "cloudCoverLow", "INTEGER")
                // Superseded by the observations rows. Safe to drop rather than migrate: they are
                // re-derivable from the next Open-Meteo fetch, which carries 31 past days.
                db.execSQL("DELETE FROM hourly_forecast_history WHERE source = 'OPEN_METEO_RETRO'")
            }
        }

        /**
         * `observations.isMetar` — true for an actual METAR, false for the ASOS 5-minute rows
         * `/stations/{id}/observations` interleaves with them.
         *
         * The cloud blend picks each station's contribution by nearest-to-the-hour, and the
         * 5-minute feed publishes EXACTLY on the hour mark while the METAR sits at :53 — so the
         * official 30-minute sky assessment could never be selected at a station publishing both,
         * losing to an instantaneous single-point sample every time.
         *
         * Backfilled as 0, not re-derived: minute-of-hour cannot distinguish the two feeds (KNUQ's
         * METARs land on :15/:35/:55) and the raw payload is long gone. Existing rows therefore
         * keep resolving by nearest-to-the-hour, and the preference takes effect as fresh rows
         * arrive — a graceful fade-in rather than a wrong guess written into history.
         */
        val MIGRATION_63_64 = object : Migration(63, 64) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "observations", "isMetar", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * `daily_history.computedHighTemp`/`computedLowTemp` become nullable, making room for
         * forecast-only rows (DailyHistoryWriter.FORECAST_ONLY_ROW): a source with no actuals
         * product (Open-Meteo, Silurian) or a day a real-actuals source never resolved (Tomorrow.io
         * before its tracking started) freezes its final forecast into the existing
         * forecastHighTemp/forecastLowTemp overlay columns, so daily history renders from the row
         * alone — no dependency on the forecasts table's retention. NULL computed* marks the
         * absence of observations and keeps the row out of accuracy baselines. Desktop moves with
         * this (its v19 uses the same DDL constant).
         *
         * Dropping NOT NULL in SQLite is a table rebuild. The DDL constant is shared with the
         * desktop schema so the two cannot drift.
         */
        val MIGRATION_64_65 = object : Migration(64, 65) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Guard: a partially-applied earlier attempt must be re-runnable.
                val computedIsNullable = run {
                    val cursor = db.query("PRAGMA table_info(daily_history)")
                    var nullable = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "computedHighTemp" &&
                            cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 0
                        ) {
                            nullable = true
                        }
                    }
                    cursor.close()
                    nullable
                }
                if (computedIsNullable) return

                db.execSQL("ALTER TABLE daily_history RENAME TO daily_history_old")
                db.execSQL(
                    com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
                        .DAILY_HISTORY_NULLABLE_COMPUTED_DDL.trimIndent(),
                )
                db.execSQL("INSERT INTO daily_history SELECT * FROM daily_history_old")
                db.execSQL("DROP TABLE daily_history_old")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_daily_history_date_locationLat_locationLon` " +
                        "ON `daily_history` (`date`, `locationLat`, `locationLon`)",
                )
            }
        }

        /**
         * Adds `observations.rawMetar` storing the original raw METAR message string for
         * diagnostics, inspection, and future re-parsing. Moves with Desktop SQLite v20.
         */
        /**
         * Adds `api` to the `observations` primary key.
         *
         * Two sources can observe the same physical station at the same instant, and those are two
         * rows, not one. Before this, REPLACE let a METAR row overwrite the NWS row for the same
         * station and timestamp and flip its `api`, removing that station from the NWS blend —
         * KNUQ, the nearest official station, was reduced to a single surviving NWS row.
         *
         * Rows already overwritten cannot be recovered; they rebuild as each source fetches. The
         * copy below is a plain INSERT SELECT because the surviving rows are, by definition, already
         * unique under the WIDER key.
         */
        val MIGRATION_66_67 = object : Migration(66, 67) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `observations_new` (
                        `stationId` TEXT NOT NULL,
                        `stationName` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `temperature` REAL NOT NULL,
                        `condition` TEXT NOT NULL,
                        `locationLat` REAL NOT NULL,
                        `locationLon` REAL NOT NULL,
                        `distanceKm` REAL NOT NULL,
                        `stationType` TEXT NOT NULL,
                        `fetchedAt` INTEGER NOT NULL,
                        `maxTempLast24h` REAL,
                        `minTempLast24h` REAL,
                        `api` TEXT NOT NULL,
                        `precipAmountMm` REAL,
                        `isWebFallback` INTEGER NOT NULL,
                        `qcFailed` INTEGER NOT NULL,
                        `cloudCover` INTEGER,
                        `cloudCoverLow` INTEGER,
                        `isMetar` INTEGER NOT NULL,
                        `rawMetar` TEXT,
                        PRIMARY KEY(`stationId`, `timestamp`, `locationLat`, `locationLon`, `api`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `observations_new` (
                        `stationId`, `stationName`, `timestamp`, `temperature`, `condition`,
                        `locationLat`, `locationLon`, `distanceKm`, `stationType`, `fetchedAt`,
                        `maxTempLast24h`, `minTempLast24h`, `api`, `precipAmountMm`,
                        `isWebFallback`, `qcFailed`, `cloudCover`, `cloudCoverLow`, `isMetar`,
                        `rawMetar`
                    )
                    SELECT
                        `stationId`, `stationName`, `timestamp`, `temperature`, `condition`,
                        `locationLat`, `locationLon`, `distanceKm`, `stationType`, `fetchedAt`,
                        `maxTempLast24h`, `minTempLast24h`, `api`, `precipAmountMm`,
                        `isWebFallback`, `qcFailed`, `cloudCover`, `cloudCoverLow`, `isMetar`,
                        `rawMetar`
                    FROM `observations`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `observations`")
                db.execSQL("ALTER TABLE `observations_new` RENAME TO `observations`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_observations_locationLat_locationLon` " +
                        "ON `observations` (`locationLat`, `locationLon`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_observations_timestamp_locationLat_locationLon` " +
                        "ON `observations` (`timestamp`, `locationLat`, `locationLon`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_observations_api` ON `observations` (`api`)",
                )
            }
        }

        val MIGRATION_65_66 = object : Migration(65, 66) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "observations", "rawMetar", "TEXT")
            }
        }

        /**
         * Persists Open-Meteo's middle and high cloud-cover bands alongside the existing total and
         * low columns. They are nullable so every other provider and every legacy row remains an
         * honest "not supplied" rather than a fabricated clear sky.
         */
        val MIGRATION_67_68 = object : Migration(67, 68) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "hourly_forecasts", "cloudCoverMid", "INTEGER")
                addColumnIfMissing(db, "hourly_forecast_history", "cloudCoverMid", "INTEGER")
                addColumnIfMissing(db, "hourly_forecasts", "cloudCoverHigh", "INTEGER")
                addColumnIfMissing(db, "hourly_forecast_history", "cloudCoverHigh", "INTEGER")
            }
        }

        /**
         * Adds graph-friendly vertical cloud fields to actual observations. Heights are whole metres
         * and the representation kind uses the stable shared enum code, never its ordinal.
         */
        val MIGRATION_68_69 = object : Migration(68, 69) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "observations", "cloudCoverMid", "INTEGER")
                addColumnIfMissing(db, "observations", "cloudCoverHigh", "INTEGER")
                addColumnIfMissing(db, "observations", "cloudBaseLowMeters", "INTEGER")
                addColumnIfMissing(db, "observations", "cloudBaseMidMeters", "INTEGER")
                addColumnIfMissing(db, "observations", "cloudBaseHighMeters", "INTEGER")
                addColumnIfMissing(db, "observations", "cloudEnvelopeBaseMeters", "INTEGER")
                addColumnIfMissing(db, "observations", "cloudEnvelopeTopMeters", "INTEGER")
                addColumnIfMissing(db, "observations", "cloudVerticalKind", "INTEGER NOT NULL DEFAULT 0")
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
                        dbName,
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
                        .addMigrations(MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48, MIGRATION_48_49, MIGRATION_49_50, MIGRATION_50_51, MIGRATION_51_52, MIGRATION_52_53, MIGRATION_53_54, MIGRATION_54_55, MIGRATION_55_56, MIGRATION_56_57, MIGRATION_57_58, MIGRATION_58_59, MIGRATION_59_60, MIGRATION_60_61, MIGRATION_61_62, MIGRATION_62_63, MIGRATION_63_64, MIGRATION_64_65, MIGRATION_65_66, MIGRATION_66_67, MIGRATION_67_68, MIGRATION_68_69)
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
