package com.weatherwidget.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies MIGRATION_44_45 adds the hourly_forecast_history table WITHOUT dropping existing data
 * (the project previously relied solely on destructive fallback). Schemas come from the
 * androidTest assets dir (configured in app/build.gradle.kts sourceSets).
 */
@RunWith(AndroidJUnit4::class)
class WeatherDatabaseMigrationTest {

    private val testDb = "migration-test-db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WeatherDatabase::class.java,
    )

    @Test
    fun migrate44To45_preservesExistingData_andCreatesHistoryTable() {
        // Seed a v44 database with one forecast row.
        helper.createDatabase(testDb, 44).apply {
            execSQL(
                "INSERT INTO forecasts (targetDate, forecastDate, locationLat, locationLon, " +
                    "locationName, highTemp, lowTemp, condition, nativeDailyIconToken, isClimateNormal, " +
                    "source, precipProbability, daytimePrecipProbability, nighttimePrecipProbability, " +
                    "periodStartTime, periodEndTime, precipAmountMm, batchFetchedAt, fetchedAt) " +
                    "VALUES (100, 99, 37.42, -122.08, 'HQ', 70.0, 50.0, 'Sunny', NULL, 0, 'NWS', " +
                    "NULL, NULL, NULL, NULL, NULL, NULL, 123, 123)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 45, true, WeatherDatabase.MIGRATION_44_45)

        // The pre-existing forecast row survived the upgrade (no destructive drop).
        db.query("SELECT COUNT(*) FROM forecasts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        // The new history table was created and is empty.
        db.query("SELECT COUNT(*) FROM hourly_forecast_history").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate45To46_preservesExistingData_andAddsPrecipColumns() {
        // Seed a v45 database with one observation and one daily extreme row.
        helper.createDatabase(testDb, 45).apply {
            execSQL(
                "INSERT INTO observations (stationId, stationName, timestamp, temperature, condition, " +
                    "locationLat, locationLon, distanceKm, stationType, fetchedAt, api) " +
                    "VALUES ('STATION1', 'Test Station', 1000, 72.0, 'Sunny', 37.42, -122.08, 1.0, 'AIRPORT', 1000, 'NWS')",
            )
            execSQL(
                "INSERT INTO daily_extremes (date, source, locationLat, locationLon, " +
                    "highTemp, lowTemp, condition, updatedAt) " +
                    "VALUES (1000, 'NWS', 37.42, -122.08, 75.0, 55.0, 'Sunny', 1000)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 46, true, WeatherDatabase.MIGRATION_45_46)

        // Existing observation row survived and precipAmountMm is null.
        db.query("SELECT COUNT(*) FROM observations WHERE precipAmountMm IS NULL").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        // Existing daily extreme row survived and all precip columns are null.
        db.query("SELECT COUNT(*) FROM daily_extremes WHERE precipAmountMm IS NULL AND precipDayMm IS NULL AND precipNightMm IS NULL").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        // Observation precip column accepts non-null values.
        db.execSQL("UPDATE observations SET precipAmountMm = 5.5 WHERE stationId = 'STATION1'")
        db.query("SELECT precipAmountMm FROM observations WHERE stationId = 'STATION1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(5.5f, c.getFloat(0), 0.01f)
        }
        // Daily extreme precip columns accept non-null values.
        db.execSQL("UPDATE daily_extremes SET precipAmountMm = 12.3, precipDayMm = 8.1, precipNightMm = 4.2 WHERE date = 1000")
        db.query("SELECT precipAmountMm, precipDayMm, precipNightMm FROM daily_extremes WHERE date = 1000").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(12.3f, c.getFloat(0), 0.01f)
            assertEquals(8.1f, c.getFloat(1), 0.01f)
            assertEquals(4.2f, c.getFloat(2), 0.01f)
        }
        db.close()
    }

    @Test
    fun migrate46To47_recreatesClimateNormalsWithRealColumns_andWipesStaleRows() {
        // Seed a v46 database with one (Int-typed) climate normal row.
        helper.createDatabase(testDb, 46).apply {
            execSQL(
                "INSERT INTO climate_normals (monthDay, locationKey, highTemp, lowTemp, fetchedAt) " +
                    "VALUES ('06-15', '37.4_-122.1', 90, 60, 1000)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 47, true, WeatherDatabase.MIGRATION_46_47)

        // Stale (wrong) normals were wiped so corrected values refetch.
        db.query("SELECT COUNT(*) FROM climate_normals").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        // Columns now accept fractional (REAL) values.
        db.execSQL(
            "INSERT INTO climate_normals (monthDay, locationKey, highTemp, lowTemp, fetchedAt) " +
                "VALUES ('06-15', '37.4_-122.1', 76.3, 54.1, 2000)",
        )
        db.query("SELECT highTemp, lowTemp FROM climate_normals WHERE monthDay = '06-15'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(76.3f, c.getFloat(0), 0.01f)
            assertEquals(54.1f, c.getFloat(1), 0.01f)
        }
        db.close()
    }

    @Test
    fun migrate47To48_collapsesJitteredHourlyFragments_keepingFreshestOnQuantizedKey() {
        // Two rows for the SAME hour/source at ~10 cm-apart coordinates (GPS jitter): a stale one and
        // a fresh one. Pre-migration the float PK keeps both; the migration must collapse them to the
        // freshest, rounded onto the quantized grid. A genuinely different marker must survive.
        helper.createDatabase(testDb, 47).apply {
            execSQL(
                "INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, " +
                    "condition, source, precipProbability, cloudCover, precipAmountMm, fetchedAt) " +
                    "VALUES (5000, 37.4168014, -122.0888977, 82.0, 'Hot', 'NWS', NULL, NULL, NULL, 100)",
            )
            execSQL(
                "INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, " +
                    "condition, source, precipProbability, cloudCover, precipAmountMm, fetchedAt) " +
                    "VALUES (5000, 37.4168434, -122.0889969, 76.0, 'Mild', 'NWS', NULL, NULL, NULL, 9000)",
            )
            // Genuinely different marker (~0.5 km away) — must NOT be collapsed into the above.
            execSQL(
                "INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, " +
                    "condition, source, precipProbability, cloudCover, precipAmountMm, fetchedAt) " +
                    "VALUES (5000, 37.4220, -122.0841, 60.0, 'Cool', 'NWS', NULL, NULL, NULL, 9999)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 48, true, WeatherDatabase.MIGRATION_47_48)

        // The two jittered fragments collapsed to one freshest row; the distinct marker survived.
        db.query("SELECT COUNT(*) FROM hourly_forecasts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }
        // Freshest wins at the user's site, on the quantized (3 dp) coordinate.
        db.query("SELECT temperature, locationLat, locationLon FROM hourly_forecasts WHERE ROUND(locationLat,3) = 37.417").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(76.0f, c.getFloat(0), 0.001f)
            assertEquals(37.417, c.getDouble(1), 0.0)
            assertEquals(-122.089, c.getDouble(2), 0.0)
        }
        db.close()
    }

    @Test
    fun migration48To49_addsIsWebFallbackColumnToObservations() {
        helper.createDatabase(testDb, 48).apply {
            execSQL(
                "INSERT INTO observations (stationId, stationName, timestamp, temperature, " +
                    "condition, locationLat, locationLon, distanceKm, stationType, fetchedAt, " +
                    "maxTempLast24h, minTempLast24h, api) " +
                    "VALUES ('KNUQ', 'Moffett Field', 1000, 72.0, 'Clear', 37.4, -122.0, 5.0, 'OFFICIAL', 2000, " +
                    "NULL, NULL, 'NWS')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 49, true, WeatherDatabase.MIGRATION_48_49)

        db.query("SELECT * FROM observations WHERE stationId = 'KNUQ'").use { c ->
            assertTrue(c.moveToFirst())
            val isWebFallbackIdx = c.getColumnIndexOrThrow("isWebFallback")
            assertEquals(0, c.getInt(isWebFallbackIdx)) // default value is 0 (false)
        }
        db.close()
    }

    @Test
    fun migrate50To51_renamesDailyExtremesToDailyHistory_preservingDataAndAddingChanceColumns() {
        // Seed a v50 database (table still named daily_extremes at this point) with one row.
        helper.createDatabase(testDb, 50).apply {
            execSQL(
                "INSERT INTO daily_extremes (date, source, locationLat, locationLon, " +
                    "highTemp, lowTemp, condition, updatedAt, precipAmountMm, precipDayMm, precipNightMm) " +
                    "VALUES (1000, 'NWS', 37.42, -122.08, 75.0, 55.0, 'Sunny', 1000, 12.3, 8.1, 4.2)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 51, true, WeatherDatabase.MIGRATION_50_51)

        // The pre-existing row survived the rename under the new table name.
        db.query("SELECT highTemp, lowTemp, precipAmountMm FROM daily_history WHERE date = 1000").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(75.0f, c.getFloat(0), 0.01f)
            assertEquals(55.0f, c.getFloat(1), 0.01f)
            assertEquals(12.3f, c.getFloat(2), 0.01f)
        }
        // New chance columns exist, default null, and accept values.
        db.query("SELECT forecastDayPrecipChance, forecastNightPrecipChance FROM daily_history WHERE date = 1000").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
        }
        db.execSQL("UPDATE daily_history SET forecastDayPrecipChance = 30, forecastNightPrecipChance = 14 WHERE date = 1000")
        db.query("SELECT forecastDayPrecipChance, forecastNightPrecipChance FROM daily_history WHERE date = 1000").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(30, c.getInt(0))
            assertEquals(14, c.getInt(1))
        }
        db.close()
    }

    @Test
    fun migrate53To54_renamesForecastDateToDateOfPrediction() {
        helper.createDatabase(testDb, 53).apply {
            execSQL(
                "INSERT INTO forecasts (targetDate, forecastDate, locationLat, locationLon, " +
                    "locationName, highTemp, lowTemp, condition, nativeDailyIconToken, isClimateNormal, " +
                    "source, precipProbability, daytimePrecipProbability, nighttimePrecipProbability, " +
                    "periodStartTime, periodEndTime, precipAmountMm, batchFetchedAt, fetchedAt) " +
                    "VALUES (100, 99, 37.42, -122.08, 'HQ', 70.0, 50.0, 'Sunny', NULL, 0, 'NWS', " +
                    "NULL, NULL, NULL, NULL, NULL, NULL, 123, 123)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 54, true, WeatherDatabase.MIGRATION_53_54)

        db.query("SELECT targetDate, dateOfPrediction FROM forecasts WHERE targetDate = 100").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(100L, c.getLong(0))
            assertEquals(99L, c.getLong(1))
        }
        db.close()
    }

    @Test
    fun migrate54To55_dropsLocationNameColumn() {
        helper.createDatabase(testDb, 54).apply {
            execSQL(
                "INSERT INTO forecasts (targetDate, dateOfPrediction, locationLat, locationLon, " +
                    "locationName, highTemp, lowTemp, condition, nativeDailyIconToken, isClimateNormal, " +
                    "source, precipProbability, daytimePrecipProbability, nighttimePrecipProbability, " +
                    "periodStartTime, periodEndTime, precipAmountMm, batchFetchedAt, fetchedAt) " +
                    "VALUES (100, 99, 37.42, -122.08, 'HQ', 70.0, 50.0, 'Sunny', NULL, 0, 'NWS', " +
                    "NULL, NULL, NULL, NULL, NULL, NULL, 123, 123)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 55, true, WeatherDatabase.MIGRATION_54_55)

        db.query("SELECT targetDate, dateOfPrediction FROM forecasts WHERE targetDate = 100").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(100L, c.getLong(0))
            assertEquals(99L, c.getLong(1))
        }
        db.close()
    }

    @Test
    fun migrate55To56_addsQcFailedColumnToObservations() {
        helper.createDatabase(testDb, 55).apply {
            execSQL(
                "INSERT INTO observations (stationId, stationName, timestamp, temperature, " +
                    "condition, locationLat, locationLon, distanceKm, stationType, fetchedAt, " +
                    "maxTempLast24h, minTempLast24h, api, precipAmountMm, isWebFallback) " +
                    "VALUES ('KPAO', 'Palo Alto Airport', 1000, 50.0, 'broken', 37.4, -122.1, 6.1, 'OFFICIAL', 2000, " +
                    "NULL, NULL, 'NWS', NULL, 1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 56, true, WeatherDatabase.MIGRATION_55_56)

        // Pre-existing rows survive and default to not-QC-failed.
        db.query("SELECT qcFailed, isWebFallback FROM observations WHERE stationId = 'KPAO'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertEquals(1, c.getInt(1))
        }
        // The column accepts the flagged state.
        db.execSQL("UPDATE observations SET qcFailed = 1 WHERE stationId = 'KPAO'")
        db.query("SELECT qcFailed FROM observations WHERE stationId = 'KPAO'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate56To57_preservesRowsAndAllowsSameStationTimestampAtTwoSites() {
        helper.createDatabase(testDb, 56).apply {
            execSQL(
                "INSERT INTO observations (stationId, stationName, timestamp, temperature, " +
                    "condition, locationLat, locationLon, distanceKm, stationType, fetchedAt, " +
                    "maxTempLast24h, minTempLast24h, api, precipAmountMm, isWebFallback, qcFailed) " +
                    "VALUES ('KPAO', 'Palo Alto Airport', 1000, 70.0, 'Fair', 37.42, -122.08, 6.1, " +
                    "'OFFICIAL', 2000, NULL, NULL, 'NWS', NULL, 0, 0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 57, true, WeatherDatabase.MIGRATION_56_57)
        db.execSQL(
            "INSERT INTO observations (stationId, stationName, timestamp, temperature, " +
                "condition, locationLat, locationLon, distanceKm, stationType, fetchedAt, " +
                "maxTempLast24h, minTempLast24h, api, precipAmountMm, isWebFallback, qcFailed) " +
                "VALUES ('KPAO', 'Palo Alto Airport', 1000, 68.0, 'Fair', 38.58, -121.49, 15.0, " +
                "'OFFICIAL', 3000, NULL, NULL, 'NWS', NULL, 0, 0)",
        )

        db.query("SELECT locationLat, fetchedAt FROM observations WHERE stationId = 'KPAO' ORDER BY locationLat").use { c ->
            assertEquals(2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(37.42, c.getDouble(0), 0.0001)
            assertEquals(2000L, c.getLong(1))
            assertTrue(c.moveToNext())
            assertEquals(38.58, c.getDouble(0), 0.0001)
            assertEquals(3000L, c.getLong(1))
        }
        db.close()
    }
    /**
     * v59 adds the station-provenance columns and clears every stored NWS "API actual". Both
     * writers that ever populated them are gone — gridpoint FORECAST values, and Open-Meteo ERA5
     * filling their gaps — so no stored value is a genuine NWS measurement. Seeded with the exact
     * shapes found on the Pixel/Samsung backups of 2026-08-08.
     */
    @Test
    fun migrate58To59_clearsAllNwsApiActualsAndAddsStationColumns() {
        val date = 20180L * 86_400_000L
        helper.createDatabase(testDb, 58).apply {
            // Gridpoint forecast filed as an actual: apiHighTemp == this row's own forecast.
            execSQL(
                "INSERT INTO daily_history (date, source, locationLat, locationLon, " +
                    "computedHighTemp, computedLowTemp, condition, updatedAt, forecastHighTemp, " +
                    "forecastLowTemp, apiHighTemp, apiLowTemp) VALUES " +
                    "($date, 'NWS', 37.417, -122.089, 75.0, 60.7, 'Clear', 2000, 82.0, 59.0, 82.0, 56.1)",
            )
            // ERA5 backfill: differs from the forecast, but it is Open-Meteo's data in NWS's row.
            execSQL(
                "INSERT INTO daily_history (date, source, locationLat, locationLon, " +
                    "computedHighTemp, computedLowTemp, condition, updatedAt, forecastHighTemp, " +
                    "forecastLowTemp, apiHighTemp, apiLowTemp) VALUES " +
                    "($date, 'NWS', 37.4168, -122.0890, 75.0, 60.7, 'Clear', 1000, 82.0, 59.0, 77.2, 56.1)",
            )
            // Open-Meteo's own ERA5 actual is legitimate and must survive untouched.
            execSQL(
                "INSERT INTO daily_history (date, source, locationLat, locationLon, " +
                    "computedHighTemp, computedLowTemp, condition, updatedAt, forecastHighTemp, " +
                    "forecastLowTemp, apiHighTemp, apiLowTemp) VALUES " +
                    "($date, 'OPEN_METEO', 37.4168, -122.0890, 75.5, 59.1, 'Clear', 1000, 74.0, 58.0, 75.5, 59.1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 59, true, WeatherDatabase.MIGRATION_58_59)

        db.query(
            "SELECT apiHighTemp, apiLowTemp, apiStationId, apiStationDistanceKm FROM daily_history " +
                "WHERE source = 'NWS'",
        ).use { c ->
            assertEquals(2, c.count)
            while (c.moveToNext()) {
                assertTrue("gridpoint-forecast and ERA5 api actuals must both be cleared", c.isNull(0))
                assertTrue(c.isNull(1))
                assertTrue("new provenance columns start empty", c.isNull(2))
                assertTrue(c.isNull(3))
            }
        }

        db.query("SELECT apiHighTemp FROM daily_history WHERE source = 'OPEN_METEO'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(75.5, c.getDouble(0), 0.001)
        }

        db.query("SELECT computedHighTemp FROM daily_history WHERE source = 'NWS' LIMIT 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("the blend must be untouched by the repair", 75.0, c.getDouble(0), 0.001)
        }
        db.close()
    }
    /** v60 adds actualsSource; existing rows get NULL and nothing else is disturbed. */
    @Test
    fun migrate59To60_addsActualsSourceWithoutTouchingOtherColumns() {
        val date = 20182L * 86_400_000L
        helper.createDatabase(testDb, 59).apply {
            execSQL(
                "INSERT INTO daily_history (date, source, locationLat, locationLon, " +
                    "computedHighTemp, computedLowTemp, condition, updatedAt, apiHighTemp, " +
                    "apiLowTemp, apiStationId, apiStationDistanceKm) VALUES " +
                    "($date, 'NWS', 37.4168, -122.0890, 75.0, 60.7, 'Clear', 1000, 75.2, 60.8, 'KNUQ', 3.83)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 60, true, WeatherDatabase.MIGRATION_59_60)

        db.query(
            "SELECT actualsSource, apiHighTemp, apiStationId, computedHighTemp FROM daily_history",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("pre-v60 rows have no recorded provenance", c.isNull(0))
            assertEquals(75.2, c.getDouble(1), 0.001)
            assertEquals("KNUQ", c.getString(2))
            assertEquals(75.0, c.getDouble(3), 0.001)
        }
        db.close()
    }
    /** v61 adds lastWriter; existing rows get NULL and nothing else is disturbed. */
    @Test
    fun migrate60To61_addsLastWriterWithoutTouchingOtherColumns() {
        val date = 20183L * 86_400_000L
        helper.createDatabase(testDb, 60).apply {
            execSQL(
                "INSERT INTO daily_history (date, source, locationLat, locationLon, " +
                    "computedHighTemp, computedLowTemp, condition, updatedAt, apiHighTemp, " +
                    "apiLowTemp, apiStationId, actualsSource) VALUES " +
                    "($date, 'NWS', 37.4168, -122.0890, 75.0, 60.7, 'Clear', 1000, 75.2, 60.8, " +
                    "'KNUQ', 'nws_station_pull')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 61, true, WeatherDatabase.MIGRATION_60_61)

        db.query("SELECT lastWriter, actualsSource, apiHighTemp, computedHighTemp FROM daily_history").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("pre-v61 rows have no recorded writer", c.isNull(0))
            assertEquals("nws_station_pull", c.getString(1))
            assertEquals(75.2, c.getDouble(2), 0.001)
            assertEquals(75.0, c.getDouble(3), 0.001)
        }
        db.close()
    }

    /**
     * v65 drops the NOT NULL constraint on daily_history.computedHighTemp/computedLowTemp so
     * forecast-only rows (DailyHistoryWriter.FORECAST_ONLY_ROW) can exist. Everything else —
     * row data, PK, index — must survive the table rebuild, and a NULL-computed row must insert
     * cleanly afterwards.
     */
    @Test
    fun migrate64To65_nullableComputedPreservesRowsAndAcceptsForecastOnlyRow() {
        val date = 20183L * 86_400_000L
        helper.createDatabase(testDb, 64).apply {
            execSQL(
                "INSERT INTO daily_history (date, source, locationLat, locationLon, " +
                    "computedHighTemp, computedLowTemp, condition, updatedAt, actualsSource, lastWriter) " +
                    "VALUES ($date, 'NWS', 37.4168, -122.0890, 75.0, 60.7, 'Clear', 1000, " +
                    "'nws_station_pull', 'forecast_freeze')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 65, true, WeatherDatabase.MIGRATION_64_65)

        // Existing row survives the rebuild untouched.
        db.query(
            "SELECT computedHighTemp, computedLowTemp, actualsSource, lastWriter, forecastHighTemp " +
                "FROM daily_history WHERE source = 'NWS'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(75.0, c.getDouble(0), 0.001)
            assertEquals(60.7, c.getDouble(1), 0.001)
            assertEquals("nws_station_pull", c.getString(2))
            assertEquals("forecast_freeze", c.getString(3))
            assertTrue(c.isNull(4))
        }
        // A forecast-only row (NULL computed) inserts cleanly — the whole point of the migration.
        db.execSQL(
            "INSERT INTO daily_history (date, source, locationLat, locationLon, computedHighTemp, " +
                "computedLowTemp, condition, updatedAt, forecastHighTemp, forecastLowTemp, lastWriter) " +
                "VALUES ($date, 'OPEN_METEO', 37.4168, -122.0890, NULL, NULL, 'Clear', 2000, " +
                "73.6, 58.3, 'forecast_only_row')",
        )
        db.query(
            "SELECT computedHighTemp, forecastHighTemp, forecastLowTemp, lastWriter " +
                "FROM daily_history WHERE source = 'OPEN_METEO'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertEquals(73.6, c.getDouble(1), 0.001)
            assertEquals(58.3, c.getDouble(2), 0.001)
            assertEquals("forecast_only_row", c.getString(3))
        }
        db.close()
    }

    @Test
    fun migrate67To68_addsMidAndHighCloudWithoutChangingExistingRows() {
        helper.createDatabase(testDb, 67).apply {
            execSQL(
                "INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, " +
                    "condition, source, precipProbability, cloudCover, precipAmountMm, fetchedAt, cloudCoverLow) " +
                    "VALUES (1000, 37.417, -122.089, 70.0, 'Cloudy', 'OPEN_METEO', 0, 100, 0.0, 2000, 4)",
            )
            execSQL(
                "INSERT INTO hourly_forecast_history (dateTime, locationLat, locationLon, temperature, " +
                    "condition, source, timestampToGroupPredictions, precipProbability, cloudCover, " +
                    "precipAmountMm, fetchedAt, cloudCoverLow) VALUES " +
                    "(1000, 37.417, -122.089, 70.0, 'Cloudy', 'OPEN_METEO', 1500, 0, 100, 0.0, 2000, 4)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 68, true, WeatherDatabase.MIGRATION_67_68)

        for (table in listOf("hourly_forecasts", "hourly_forecast_history")) {
            db.query("SELECT cloudCover, cloudCoverLow, cloudCoverMid, cloudCoverHigh FROM $table").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(100, c.getInt(0))
                assertEquals(4, c.getInt(1))
                assertTrue("legacy $table mid cloud must remain unknown", c.isNull(2))
                assertTrue("legacy $table high cloud must remain unknown", c.isNull(3))
            }
        }
        db.close()
    }

    @Test
    fun migrate68To69_addsFlatObservedCloudFieldsAndPreservesLegacyRows() {
        helper.createDatabase(testDb, 68).apply {
            execSQL(
                "INSERT INTO observations (stationId, stationName, timestamp, temperature, " +
                    "condition, locationLat, locationLon, distanceKm, stationType, fetchedAt, api, " +
                    "isWebFallback, qcFailed, isMetar) VALUES " +
                    "('KNUQ', 'Moffett', 1000, 61.0, 'Cloudy', 37.417, -122.089, 2.0, " +
                    "'OFFICIAL', 2000, 'NWS', 0, 0, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 69, true, WeatherDatabase.MIGRATION_68_69)

        db.query(
            "SELECT cloudCoverMid, cloudCoverHigh, cloudBaseLowMeters, cloudBaseMidMeters, " +
                "cloudBaseHighMeters, cloudEnvelopeBaseMeters, cloudEnvelopeTopMeters, " +
                "cloudVerticalKind FROM observations WHERE stationId = 'KNUQ'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            for (index in 0..6) {
                assertTrue("legacy vertical cloud column $index remains unknown", c.isNull(index))
            }
            assertEquals(0, c.getInt(7))
        }

        db.execSQL(
            "UPDATE observations SET cloudCoverMid = 75, cloudCoverHigh = 19, " +
                "cloudBaseLowMeters = 305, cloudBaseMidMeters = 3048, " +
                "cloudBaseHighMeters = 9144, cloudEnvelopeBaseMeters = 305, " +
                "cloudEnvelopeTopMeters = 10000, cloudVerticalKind = 20 " +
                "WHERE stationId = 'KNUQ'",
        )
        db.query(
            "SELECT cloudCoverMid, cloudCoverHigh, cloudBaseLowMeters, cloudBaseMidMeters, " +
                "cloudBaseHighMeters, cloudEnvelopeBaseMeters, cloudEnvelopeTopMeters, " +
                "cloudVerticalKind FROM observations WHERE stationId = 'KNUQ'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(75, c.getInt(0))
            assertEquals(19, c.getInt(1))
            assertEquals(305, c.getInt(2))
            assertEquals(3_048, c.getInt(3))
            assertEquals(9_144, c.getInt(4))
            assertEquals(305, c.getInt(5))
            assertEquals(10_000, c.getInt(6))
            assertEquals(20, c.getInt(7))
        }
        db.close()
    }

    /**
     * The 2026-08-31 defect, replayed through the migration. The corrupt KPAO report and the KRHV
     * malformed-group report were both stored unflagged before MetarPlausibility existed; the
     * migration must re-judge them without touching their valid neighbours or any row that carries
     * no raw report.
     */
    @Test
    fun migrate69To70_flagsStoredImpossibleReadingsAndLeavesValidOnesAlone() {
        fun insert(id: String, ts: Long, temp: Double, raw: String?) =
            "INSERT INTO observations (stationId, stationName, timestamp, temperature, " +
                "condition, locationLat, locationLon, distanceKm, stationType, fetchedAt, api, " +
                "isWebFallback, qcFailed, isMetar, rawMetar, cloudVerticalKind) VALUES " +
                "('$id', '$id site', $ts, $temp, 'Fair', 37.417, -122.089, 5.0, " +
                "'OFFICIAL', 2000, 'NWS', 0, 0, 1, ${raw?.let { "'$it'" } ?: "NULL"}, 0)"

        helper.createDatabase(testDb, 69).apply {
            // Dewpoint 12 C above a temperature of 10 C — impossible.
            execSQL(insert("KPAO", 2000, 50.0, "METAR KPAO 312347Z 32014G22KT 10SM SCT040 10/12 A2993"))
            // Its own valid neighbour an hour earlier.
            execSQL(insert("KPAO", 1000, 69.8, "KPAO 312247Z 33018G20KT 10SM SCT040 21/12 A2993"))
            // Three-digit temperature field — structurally invalid.
            execSQL(insert("KRHV", 3000, 48.2, "METAR KRHV 271547Z 00000KT 10SM FEW080 209/14 A2996"))
            // No raw report: nothing to cross-check, must be left alone.
            execSQL(insert("KNUQ", 4000, 68.0, null))
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 70, true, WeatherDatabase.MIGRATION_69_70)

        fun qcFailedFor(id: String, ts: Long): Int =
            db.query("SELECT qcFailed FROM observations WHERE stationId = '$id' AND timestamp = $ts")
                .use { c ->
                    assertTrue("row $id@$ts survived the migration", c.moveToFirst())
                    c.getInt(0)
                }

        assertEquals("impossible dewpoint must be flagged", 1, qcFailedFor("KPAO", 2000))
        assertEquals("malformed temp group must be flagged", 1, qcFailedFor("KRHV", 3000))
        assertEquals("the valid neighbour must be untouched", 0, qcFailedFor("KPAO", 1000))
        assertEquals("a row with no raw report must be untouched", 0, qcFailedFor("KNUQ", 4000))

        // The flagged rows are kept, not deleted — the stations UI still shows them.
        db.query("SELECT COUNT(*) FROM observations").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(4, c.getInt(0))
        }
    }
}
