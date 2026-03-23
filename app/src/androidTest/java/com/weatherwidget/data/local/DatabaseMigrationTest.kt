package com.weatherwidget.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for database migration paths.
 * Schemas are exported to app/schemas/ and start at version 9.
 *
 * These tests verify that each migration runs without crashing and
 * that data inserted before migration is still accessible afterward.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    private val testDb = "migration_test_db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WeatherDatabase::class.java,
    )

    @Test
    fun migrate9to10() {
        // Create DB at v9, insert data, then migrate
        helper.createDatabase(testDb, 9).apply {
            execSQL(
                """
                INSERT INTO weather_data (date, locationLat, locationLon, locationName, highTemp, lowTemp, currentTemp, `condition`, isActual, source, stationId, fetchedAt)
                VALUES ('2026-02-20', 37.42, -122.08, 'Test', 65.0, 45.0, 55.0, 'Sunny', 0, 'NWS', NULL, ${System.currentTimeMillis()})
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(testDb, 10, true, WeatherDatabase.MIGRATION_9_10)
    }

    @Test
    fun migrate10to11() {
        helper.createDatabase(testDb, 10).apply {
            execSQL(
                """
                INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, source, fetchedAt)
                VALUES ('2026-02-20T14:00', 37.42, -122.08, 60.0, 'NWS', ${System.currentTimeMillis()})
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(testDb, 11, true, WeatherDatabase.MIGRATION_10_11)
    }

    @Test
    fun migrate11to12() {
        helper.createDatabase(testDb, 11).apply {
            execSQL(
                """
                INSERT INTO forecast_snapshots (targetDate, forecastDate, locationLat, locationLon, highTemp, lowTemp, `condition`, source, fetchedAt)
                VALUES ('2026-02-21', '2026-02-20', 37.42, -122.08, 70.0, 50.0, 'Sunny', 'NWS', 1000)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(testDb, 12, true, WeatherDatabase.MIGRATION_11_12)
    }

    @Test
    fun migrate12to14() {
        helper.createDatabase(testDb, 12).apply {
            close()
        }

        helper.runMigrationsAndValidate(testDb, 14, true, WeatherDatabase.MIGRATION_12_14)
    }

    @Test
    fun migrate14to15() {
        helper.createDatabase(testDb, 14).apply {
            close()
        }

        helper.runMigrationsAndValidate(testDb, 15, true, WeatherDatabase.MIGRATION_14_15)
    }

    @Test
    fun migrate15to16() {
        helper.createDatabase(testDb, 15).apply {
            close()
        }

        helper.runMigrationsAndValidate(testDb, 16, true, WeatherDatabase.MIGRATION_15_16)
    }

    @Test
    fun migrate16to17() {
        helper.createDatabase(testDb, 16).apply {
            close()
        }

        helper.runMigrationsAndValidate(testDb, 17, true, WeatherDatabase.MIGRATION_16_17)
    }

    @Test
    fun migrate17to18() {
        helper.createDatabase(testDb, 17).apply {
            // Insert data with integer temps (pre-migration schema)
            execSQL(
                """
                INSERT INTO weather_data (date, locationLat, locationLon, locationName, highTemp, lowTemp, currentTemp, `condition`, isActual, isClimateNormal, source, stationId, precipProbability, fetchedAt)
                VALUES ('2026-02-20', 37.42, -122.08, 'Test', 65, 45, 55, 'Sunny', 0, 0, 'NWS', NULL, 30, ${System.currentTimeMillis()})
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO forecast_snapshots (targetDate, forecastDate, locationLat, locationLon, highTemp, lowTemp, `condition`, source, fetchedAt)
                VALUES ('2026-02-21', '2026-02-20', 37.42, -122.08, 70, 50, 'Cloudy', 'NWS', 1000)
                """.trimIndent(),
            )
            close()
        }

        // v17→18 converts INTEGER temps to REAL
        val db = helper.runMigrationsAndValidate(testDb, 18, true, WeatherDatabase.MIGRATION_17_18)

        // Verify data survived the INTEGER→REAL conversion
        val cursor = db.query("SELECT highTemp, lowTemp FROM weather_data WHERE date = '2026-02-20'")
        cursor.moveToFirst()
        val highTemp = cursor.getFloat(0)
        val lowTemp = cursor.getFloat(1)
        cursor.close()

        assert(highTemp == 65.0f) { "Expected 65.0 but got $highTemp" }
        assert(lowTemp == 45.0f) { "Expected 45.0 but got $lowTemp" }
    }

    @Test
    fun migrate18to19() {
        helper.createDatabase(testDb, 18).apply {
            execSQL(
                """
                INSERT INTO weather_data (date, locationLat, locationLon, locationName, highTemp, lowTemp, currentTemp, `condition`, isActual, isClimateNormal, source, stationId, precipProbability, fetchedAt)
                VALUES ('2026-02-20', 37.42, -122.08, 'Test', 65.0, 45.0, 55.0, 'Sunny', 0, 0, 'NWS', NULL, NULL, ${System.currentTimeMillis()})
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 19, true, WeatherDatabase.MIGRATION_18_19)

        // Verify the new currentTempObservedAt column exists and defaults to NULL
        val cursor = db.query("SELECT currentTempObservedAt FROM weather_data WHERE date = '2026-02-20'")
        cursor.moveToFirst()
        assert(cursor.isNull(0)) { "currentTempObservedAt should default to NULL" }
        cursor.close()
    }

    @Test
    fun migrateFullChain_9to19() {
        helper.createDatabase(testDb, 9).apply {
            execSQL(
                """
                INSERT INTO weather_data (date, locationLat, locationLon, locationName, highTemp, lowTemp, currentTemp, `condition`, isActual, source, stationId, fetchedAt)
                VALUES ('2026-02-20', 37.42, -122.08, 'Test', 65, 45, 55, 'Sunny', 0, 'NWS', 'KSFO', ${System.currentTimeMillis()})
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            testDb,
            19,
            true,
            WeatherDatabase.MIGRATION_9_10,
            WeatherDatabase.MIGRATION_10_11,
            WeatherDatabase.MIGRATION_11_12,
            WeatherDatabase.MIGRATION_12_14,
            WeatherDatabase.MIGRATION_14_15,
            WeatherDatabase.MIGRATION_15_16,
            WeatherDatabase.MIGRATION_16_17,
            WeatherDatabase.MIGRATION_17_18,
            WeatherDatabase.MIGRATION_18_19,
        )
    }

    @Test
    fun migrate28to29() {
        helper.createDatabase(testDb, 28).apply {
            close()
        }

        helper.runMigrationsAndValidate(testDb, 29, true, WeatherDatabase.MIGRATION_28_29)
    }

    @Test
    fun migrate33to34() {
        helper.createDatabase(testDb, 33).apply {
            execSQL(
                """
                INSERT INTO observations (stationId, stationName, timestamp, temperature, `condition`, locationLat, locationLon, distanceKm, stationType, fetchedAt)
                VALUES ('KTEST', 'Test Station', ${System.currentTimeMillis()}, 65.0, 'Clear', 37.42, -122.08, 5.0, 'ASOS', ${System.currentTimeMillis()})
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 34, true, WeatherDatabase.MIGRATION_33_34)
        val cursor = db.query("SELECT maxTempLast24h, minTempLast24h FROM observations LIMIT 1")
        cursor.moveToFirst()
        // Columns exist and are null (not populated by migration)
        assert(cursor.isNull(cursor.getColumnIndex("maxTempLast24h")))
        assert(cursor.isNull(cursor.getColumnIndex("minTempLast24h")))
        cursor.close()
    }

    @Test
    fun migrate34to35() {
        helper.createDatabase(testDb, 34).apply {
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 35, true, WeatherDatabase.MIGRATION_34_35)

        // Verify table exists and can accept a row
        db.execSQL(
            """
            INSERT INTO daily_extremes (date, source, locationLat, locationLon, highTemp, lowTemp, `condition`, updatedAt)
            VALUES ('2026-03-18', 'NWS', 37.42, -122.08, 72.0, 50.0, 'Clear', ${System.currentTimeMillis()})
            """.trimIndent(),
        )
        val cursor = db.query("SELECT highTemp, lowTemp FROM daily_extremes WHERE date = '2026-03-18'")
        cursor.moveToFirst()
        assert(cursor.getFloat(0) == 72.0f) { "Expected highTemp=72.0 but got ${cursor.getFloat(0)}" }
        assert(cursor.getFloat(1) == 50.0f) { "Expected lowTemp=50.0 but got ${cursor.getFloat(1)}" }
        cursor.close()
    }

    @Test
    fun migrate35to36() {
        helper.createDatabase(testDb, 35).apply {
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 36, true, WeatherDatabase.MIGRATION_35_36)
        
        // Verify the new index exists on the forecasts table
        val cursor = db.query("PRAGMA index_list(forecasts)")
        var found = false
        while (cursor.moveToNext()) {
            val name = cursor.getString(cursor.getColumnIndex("name"))
            if (name == "index_forecasts_targetDate_source_locationLat_locationLon_batchFetchedAt") {
                found = true
                break
            }
        }
        cursor.close()
        assert(found) { "Optimized composite index should exist after migration 35 to 36" }
    }

    @Test
    fun migrate37to38() {
        helper.createDatabase(testDb, 37).apply {
            execSQL(
                """
                INSERT INTO observations (stationId, stationName, timestamp, temperature, `condition`, locationLat, locationLon, distanceKm, stationType, fetchedAt)
                VALUES ('KTEST', 'Test Station', ${System.currentTimeMillis()}, 65.0, 'Clear', 37.42, -122.08, 5.0, 'ASOS', ${System.currentTimeMillis()})
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 38, true, WeatherDatabase.MIGRATION_37_38)
        val cursor = db.query("SELECT api FROM observations LIMIT 1")
        cursor.moveToFirst()
        assert(cursor.getString(0) == "NWS") { "Expected api='NWS' but got '${cursor.getString(0)}'" }
        cursor.close()
    }

    @Test
    fun migrate38to39() {
        helper.createDatabase(testDb, 38).apply {
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 39, true, WeatherDatabase.MIGRATION_38_39)

        // Verify the new index exists on the observations table
        val cursor = db.query("PRAGMA index_list(observations)")
        var found = false
        while (cursor.moveToNext()) {
            val name = cursor.getString(cursor.getColumnIndex("name"))
            if (name == "index_observations_api") {
                found = true
                break
            }
        }
        cursor.close()
        assert(found) { "Index on api column should exist after migration 38 to 39" }
    }

    @Test
    fun migrate39to40() {
        val testDateTime = "2026-03-22T10:00"
        val expectedEpoch = java.time.LocalDateTime.parse(testDateTime)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        helper.createDatabase(testDb, 39).apply {
            execSQL(
                """
                INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, source, `condition`, fetchedAt)
                VALUES ('$testDateTime', 37.42, -122.08, 65.0, 'NWS', 'Clear', ${System.currentTimeMillis()})
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 40, true, WeatherDatabase.MIGRATION_39_40)
        val cursor = db.query("SELECT dateTime FROM hourly_forecasts LIMIT 1")
        cursor.moveToFirst()
        val migratedEpoch = cursor.getLong(0)
        cursor.close()

        assert(migratedEpoch == expectedEpoch) { "Expected $expectedEpoch but got $migratedEpoch for $testDateTime" }
    }

    @Test
    fun migrate40to41() {
        val testDate = "2026-03-22"
        val expectedDateEpoch = java.time.LocalDate.parse(testDate).toEpochDay() * 86400_000L
        val now = System.currentTimeMillis()

        helper.createDatabase(testDb, 40).apply {
            execSQL("INSERT INTO api_usage_stats VALUES ('$testDate', 'NWS', 5)")
            execSQL(
                """
                INSERT INTO forecasts (targetDate, forecastDate, locationLat, locationLon, locationName, highTemp, lowTemp, `condition`, isClimateNormal, source, precipProbability, periodStartTime, periodEndTime, batchFetchedAt, fetchedAt)
                VALUES ('$testDate', '2026-03-21', 37.42, -122.08, 'Test', 72.0, 50.0, 'Clear', 0, 'NWS', 20, '2026-03-22T06:00:00+00:00', '2026-03-22T18:00:00+00:00', $now, $now)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO daily_extremes (date, source, locationLat, locationLon, highTemp, lowTemp, `condition`, updatedAt)
                VALUES ('$testDate', 'NWS', 37.42, -122.08, 72.0, 50.0, 'Clear', $now)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 41, true, WeatherDatabase.MIGRATION_40_41)

        // Verify api_usage_stats date conversion
        val apiCursor = db.query("SELECT `date` FROM api_usage_stats")
        apiCursor.moveToFirst()
        val migratedApiDate = apiCursor.getLong(0)
        apiCursor.close()
        assert(migratedApiDate == expectedDateEpoch) { "api_usage_stats: expected $expectedDateEpoch but got $migratedApiDate" }

        // Verify forecasts date conversion and periodStartTime/periodEndTime nulled out
        val fCursor = db.query("SELECT targetDate, forecastDate, periodStartTime, periodEndTime FROM forecasts")
        fCursor.moveToFirst()
        val migratedTargetDate = fCursor.getLong(0)
        val migratedForecastDate = fCursor.getLong(1)
        val expectedForecastEpoch = java.time.LocalDate.parse("2026-03-21").toEpochDay() * 86400_000L
        assert(migratedTargetDate == expectedDateEpoch) { "forecasts targetDate: expected $expectedDateEpoch but got $migratedTargetDate" }
        assert(migratedForecastDate == expectedForecastEpoch) { "forecasts forecastDate: expected $expectedForecastEpoch but got $migratedForecastDate" }
        assert(fCursor.isNull(2)) { "periodStartTime should be NULL after migration" }
        assert(fCursor.isNull(3)) { "periodEndTime should be NULL after migration" }
        fCursor.close()

        // Verify daily_extremes date conversion
        val dCursor = db.query("SELECT `date` FROM daily_extremes")
        dCursor.moveToFirst()
        val migratedExtremeDate = dCursor.getLong(0)
        dCursor.close()
        assert(migratedExtremeDate == expectedDateEpoch) { "daily_extremes: expected $expectedDateEpoch but got $migratedExtremeDate" }
    }
}
