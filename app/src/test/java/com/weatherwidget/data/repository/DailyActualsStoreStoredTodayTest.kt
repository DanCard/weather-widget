package com.weatherwidget.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.StartupActualsRefresh
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * Integration: the provisional startup actuals over a real Room database. The real recompute writes
 * today's `daily_history` row; [DailyActualsStore.getDailyActualsStored] must then paint exactly
 * what the live blend computes, so the startup's second pass ([StartupActualsRefresh]) repaints
 * nothing. Once a newer observation lands, the stored row is still painted and the live pass
 * repaints the change (user decision 2026-11-18).
 * performance/261118-cold-start-daily-actuals-cost.md
 */
@Category(LongDuration::class)
class DailyActualsStoreStoredTodayTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private lateinit var store: DailyActualsStore
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val lat = 37.4168205
    private val lon = -122.0890350
    private val source = WeatherSource.NWS.id
    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now()

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = DailyActualsStore(
            db.observationDao(),
            db.dailyHistoryDao(),
            db.appLogDao(),
            db.hourlyForecastDao(),
            PersonalStationWeightProvider { 1.0 },
        )
    }

    @After
    fun teardown() = db.close()

    private fun obs(timestamp: Long, temp: Float, stationId: String = "KNUQ") = ObservationEntity(
        stationId = stationId,
        stationName = stationId,
        timestamp = timestamp,
        temperature = temp,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        distanceKm = 2f,
        stationType = StationType.OFFICIAL,
        fetchedAt = timestamp,
        api = source,
    )

    /** Every 10 minutes from local midnight up to a minute ago, two stations, so the blend has work. */
    private fun todaySoFar(): List<ObservationEntity> {
        val startMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = System.currentTimeMillis() - 60_000L
        return generateSequence(startMs) { it + 600_000L }
            .takeWhile { it <= endMs }
            .flatMapIndexed { i, ts ->
                sequenceOf(obs(ts, 50f + (i % 36) / 2f), obs(ts + 120_000L, 51f + (i % 30) / 2f, "KPAO"))
            }
            .filter { it.timestamp <= endMs }
            .toList()
    }

    @Test
    fun `stored today equals the live blend after a recompute, so the second pass repaints nothing`() = runTest {
        db.observationDao().insertAll(todaySoFar())
        store.recomputeDailyExtremesForDay(lat, lon, today)

        val stored = store.getDailyActualsStored(lat, lon, listOf(source))
        val live = store.getDailyActualsWithLiveToday(lat, lon, emptyList(), listOf(source))

        val storedToday = stored[source]?.get(today)
        val liveToday = live[source]?.get(today)
        println("DailyActualsStoreStoredTodayTest: stored=$storedToday live=$liveToday")
        assertNotNull("the recompute's row is painted provisionally", storedToday)
        assertNotNull(liveToday)
        assertEquals(liveToday!!.computedHighTemp!!, storedToday!!.computedHighTemp!!, 0f)
        assertEquals(liveToday.computedLowTemp!!, storedToday.computedLowTemp!!, 0f)
        val changed = StartupActualsRefresh.changedSources(stored, live)
        if (changed.isNotEmpty()) println("DailyActualsStoreStoredTodayTest: changed=$changed stored=$stored live=$live")
        assertEquals("every field the widget draws must match, or the live pass repaints", emptySet<String>(), changed)
    }

    /** User decision 2026-11-18: the last recompute's row is painted, and the live pass corrects it. */
    @Test
    fun `a row older than the newest observation is painted and the live pass repaints its change`() = runTest {
        db.observationDao().insertAll(todaySoFar())
        store.recomputeDailyExtremesForDay(lat, lon, today)
        Thread.sleep(5)
        // A record high after the recompute: the stored row has not seen it.
        db.observationDao().insertAll(listOf(obs(System.currentTimeMillis(), 90f)))

        val stored = store.getDailyActualsStored(lat, lon, listOf(source))
        val live = store.getDailyActualsWithLiveToday(lat, lon, emptyList(), listOf(source))

        println("DailyActualsStoreStoredTodayTest: older stored=${stored[source]?.get(today)} live=${live[source]?.get(today)}")
        val storedHigh = stored[source]?.get(today)?.computedHighTemp
        assertNotNull("the last recompute's row is painted, not nothing", storedHigh)
        assertTrue("it predates the 90°F reading", storedHigh!! < 80f)
        assertEquals(90f, live[source]!![today]!!.computedHighTemp!!, 0.5f)
        val changed = StartupActualsRefresh.changedSources(stored, live)
        assertTrue(source in changed)
        assertEquals(
            listOf(7),
            StartupActualsRefresh.widgetsToRepaint(
                mapOf(7 to source, 8 to "SILURIAN"),
                changed,
                mapOf(source to stored.getValue(source), "SILURIAN" to mapOf(today to live.getValue(source).getValue(today).copy(source = "SILURIAN"))),
                today,
            ).toList(),
        )
    }
}
