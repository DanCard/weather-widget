package com.weatherwidget.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * A settled day must stay settled across a process restart.
 *
 * The signature cache was in-memory ("one redundant recompute on process death"); on the Pixel
 * 7 Pro that one recompute was 12 s of cold CPU alongside the user's first tap after an install
 * (`performance/260910-post-install-cold-start-storm.md`). Two store instances sharing one
 * [ReducedSignatureStore] stand in for two processes sharing one preference file.
 */
@Category(LongDuration::class)
class DailyActualsStoreSignaturePersistenceTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val lat = 37.4168205
    private val lon = -122.0890350
    private val day: LocalDate = LocalDate.now().minusDays(2)
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() = db.close()

    private fun store(signatures: ReducedSignatureStore) =
        DailyActualsStore(
            db.observationDao(),
            db.dailyHistoryDao(),
            db.appLogDao(),
            db.hourlyForecastDao(),
            PersonalStationWeightProvider { 1.0 },
            signatures,
        )

    private fun at(hour: Int): Long =
        day.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    private fun obs(timestamp: Long, temp: Float) =
        ObservationEntity(
            stationId = "KNUQ",
            stationName = "Moffett Field",
            timestamp = timestamp,
            temperature = temp,
            condition = "Clear",
            locationLat = lat,
            locationLon = lon,
            distanceKm = 2f,
            stationType = "OFFICIAL",
            fetchedAt = timestamp,
            api = WeatherSource.NWS.id,
        )

    private suspend fun skipCount() = db.appLogDao().getLogsByTag("DAILY_RECOMPUTE_SKIP", 100).size

    private suspend fun recomputeCount() = db.appLogDao().getLogsByTag("DAILY_HISTORY_STABLE", 100).size +
        db.dailyHistoryDao().getExtremesInRange(day.toEpochDay() * 86_400_000L, day.toEpochDay() * 86_400_000L, lat, lon).size

    @Test
    fun `a second store instance skips a day the first one settled`() = runTest {
        db.observationDao().insertAll(listOf(obs(at(3), 55f), obs(at(9), 62f), obs(at(15), 71f), obs(at(21), 60f)))
        val shared = InMemoryReducedSignatureStore()

        store(shared).recomputeDailyExtremesForDay(lat, lon, day, emptyList())
        assertEquals("first reduction must run", 0, skipCount())

        // "Process restart": a fresh store, same persisted signatures.
        store(shared).recomputeDailyExtremesForDay(lat, lon, day, emptyList())
        assertEquals("unchanged day must be skipped by the new instance", 1, skipCount())
    }

    @Test
    fun `an in-memory-only store recomputes after a restart`() = runTest {
        db.observationDao().insertAll(listOf(obs(at(3), 55f), obs(at(9), 62f), obs(at(15), 71f), obs(at(21), 60f)))

        store(InMemoryReducedSignatureStore()).recomputeDailyExtremesForDay(lat, lon, day, emptyList())
        store(InMemoryReducedSignatureStore()).recomputeDailyExtremesForDay(lat, lon, day, emptyList())

        assertEquals("proves the shared store is what carries the skip", 0, skipCount())
    }

    @Test
    fun `new observations and force both still recompute`() = runTest {
        db.observationDao().insertAll(listOf(obs(at(3), 55f), obs(at(9), 62f), obs(at(15), 71f), obs(at(21), 60f)))
        val shared = InMemoryReducedSignatureStore()

        store(shared).recomputeDailyExtremesForDay(lat, lon, day, emptyList())
        db.observationDao().insertAll(listOf(obs(at(18), 68f)))
        store(shared).recomputeDailyExtremesForDay(lat, lon, day, emptyList())
        assertEquals("changed observations must not be skipped", 0, skipCount())

        store(shared).recomputeDailyExtremesForDay(lat, lon, day, emptyList(), force = true)
        assertEquals("force must bypass the signature", 0, skipCount())
    }
}
