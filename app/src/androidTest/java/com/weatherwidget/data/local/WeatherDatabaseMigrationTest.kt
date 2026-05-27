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
}
