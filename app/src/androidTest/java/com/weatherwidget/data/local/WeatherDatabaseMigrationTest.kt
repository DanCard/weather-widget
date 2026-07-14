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
}
