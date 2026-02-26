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
}
