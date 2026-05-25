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
}
