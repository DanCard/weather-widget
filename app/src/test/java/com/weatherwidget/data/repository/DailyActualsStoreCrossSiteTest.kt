package com.weatherwidget.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.DailyActualsSource
import com.weatherwidget.shared.actuals.DailyHistoryWriter
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
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
 * `DailyActualsStore.persistExtremes` must only write the `daily_history` fragment belonging to the
 * site its blend was anchored at.
 *
 * Regression for the Samsung 2026-08-22 incident (plans/260822-fix-cross-site-actuals-clobber.md):
 * a GPS excursion promoted a site whose observations for the day began at 12:00, and the recompute
 * anchored there overwrote the *home* row 800 m away —
 * `DAILY_HISTORY_OVERWRITE date=2026-08-22 src=TOMORROW_IO at=37.41682… low=57.03->66.52` —
 * destroying a value built from 40 rows spanning the whole day.
 *
 * The read side (`ObservationDao.getObservationsInRange`) already collapses to the nearest site;
 * the write side did not, so `getExtremesInRange`'s coarse ±0.1° box (~7 mi) handed back foreign
 * fragments and every one of them got the anchored blend.
 */
@Category(LongDuration::class)
class DailyActualsStoreCrossSiteTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private lateinit var store: DailyActualsStore
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** The real coordinates from the incident. */
    private val homeLat = 37.4168205
    private val homeLon = -122.0890350

    /** "Amphitheatre Parkway" — promoted 18:40:58, ~0.82 km away, observations only from 12:00. */
    private val stubLat = 37.4242298
    private val stubLon = -122.0883022

    private val source = WeatherSource.NWS.id
    private val today: LocalDate = LocalDate.now()
    private val zone: ZoneId = ZoneId.systemDefault()

    private fun dateMillis(date: LocalDate) = date.toEpochDay() * 86_400_000L

    /** Local wall-clock hour:minute on [today], as epoch ms. */
    private fun at(hour: Int, minute: Int = 0): Long =
        today.atStartOfDay(zone).plusHours(hour.toLong()).plusMinutes(minute.toLong())
            .toInstant().toEpochMilli()

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
    fun teardown() {
        db.close()
    }

    private fun obs(
        lat: Double,
        lon: Double,
        timestamp: Long,
        temp: Float,
        stationId: String = "KNUQ",
    ) = ObservationEntity(
        stationId = stationId,
        stationName = "Moffett Field",
        timestamp = timestamp,
        temperature = temp,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        distanceKm = 2f,
        stationType = "OFFICIAL",
        fetchedAt = timestamp,
        api = source,
    )

    private fun historyRow(
        lat: Double,
        lon: Double,
        high: Float,
        low: Float,
        actualsSource: String? = null,
    ) = DailyHistoryEntity(
        date = dateMillis(today),
        source = source,
        locationLat = lat,
        locationLon = lon,
        computedHighTemp = high,
        computedLowTemp = low,
        condition = "Clear",
        updatedAt = 1L,
        actualsSource = actualsSource,
        lastWriter = DailyHistoryWriter.BLEND_RECOMPUTE.storedValue,
    )

    private suspend fun rowAt(lat: Double, lon: Double): DailyHistoryEntity? =
        db.dailyHistoryDao()
            .getExtremesInRange(dateMillis(today), dateMillis(today), lat, lon)
            .firstOrNull { it.locationLat == lat && it.locationLon == lon }

    /** Home: full day of observations, real overnight low. */
    private suspend fun seedHomeFullDay() {
        val rows = buildList {
            add(obs(homeLat, homeLon, at(0), 60.3f))
            add(obs(homeLat, homeLon, at(6, 47), 57.0f))
            add(obs(homeLat, homeLon, at(12), 66.5f))
            add(obs(homeLat, homeLon, at(16, 40), 72.3f))
            add(obs(homeLat, homeLon, at(19), 69.2f))
        }
        db.observationDao().insertAll(rows)
    }

    /** Stub: afternoon only, exactly like the promoted excursion site. */
    private suspend fun seedStubAfternoonOnly() {
        db.observationDao().insertAll(
            listOf(
                obs(stubLat, stubLon, at(12), 66.5f),
                obs(stubLat, stubLon, at(16, 40), 72.3f),
                obs(stubLat, stubLon, at(19), 69.2f),
            ),
        )
    }

    @Test
    fun `recompute anchored at the excursion site leaves the home row intact`() = runTest {
        seedHomeFullDay()
        seedStubAfternoonOnly()
        db.dailyHistoryDao().insertAll(listOf(historyRow(homeLat, homeLon, high = 72.3f, low = 57.0f)))

        // Anchored at the stub, exactly as the widget was at 18:41:58.
        store.recomputeDailyExtremesForDay(stubLat, stubLon, today, emptyList())

        val home = rowAt(homeLat, homeLon)
        assertNotNull("home row must still exist", home)
        assertEquals(
            "home low must not inherit the stub's truncated (afternoon-only) minimum",
            57.0f,
            home!!.computedLowTemp!!,
            0.01f,
        )
    }

    @Test
    fun `sub-precision jitter of the anchor still merges into the same row`() = runTest {
        seedHomeFullDay()
        // 0.0001 deg (~11 m) — GPS jitter, well inside SAME_SITE_TOLERANCE_DEG.
        val jitterLat = homeLat + 0.0001
        val jitterLon = homeLon + 0.0001
        db.dailyHistoryDao().insertAll(listOf(historyRow(jitterLat, jitterLon, high = 1f, low = 1f)))

        store.recomputeDailyExtremesForDay(homeLat, homeLon, today, emptyList())

        val all = db.dailyHistoryDao()
            .getExtremesInRange(dateMillis(today), dateMillis(today), homeLat, homeLon)
            .filter { it.source == source }
        assertEquals("jitter must update the existing row, not orphan a second one", 1, all.size)
        assertTrue(
            "the jittered row should have been recomputed away from its placeholder",
            all.single().computedLowTemp!! < 60f,
        )
    }

    @Test
    fun `a genuinely new site inserts its own row and does not touch home`() = runTest {
        seedHomeFullDay()
        seedStubAfternoonOnly()
        db.dailyHistoryDao().insertAll(listOf(historyRow(homeLat, homeLon, high = 72.3f, low = 57.0f)))

        store.recomputeDailyExtremesForDay(stubLat, stubLon, today, emptyList())

        // Documents the deliberate scope limit: the stub gets its own (truncated) row. Making the
        // DISPLAY correct while standing there is the backfill/forecast-fallback plan, not this fix.
        val stub = rowAt(stubLat, stubLon)
        assertNotNull("the anchor site should get its own row", stub)
        assertEquals(
            "home row is untouched by the stub's insert",
            57.0f,
            rowAt(homeLat, homeLon)!!.computedLowTemp!!,
            0.01f,
        )
    }

    @Test
    fun `today low is suppressed when the day's observations start late`() = runTest {
        seedStubAfternoonOnly()

        val actuals = store.getDailyActualsWithLiveToday(stubLat, stubLon, emptyList(), listOf(source))

        val todayRow = actuals[source]?.get(today)
        assertNotNull("today should still have an entry", todayRow)
        assertNull(
            "a noon-onward window must not report a day low (it would render as a settled actual)",
            todayRow!!.computedLowTemp,
        )
        assertNotNull(
            "the high is unaffected — a late start under-reports it, it does not fabricate it",
            todayRow.computedHighTemp,
        )
    }

    @Test
    fun `today low survives when the day is covered from its start`() = runTest {
        seedHomeFullDay()

        val actuals = store.getDailyActualsWithLiveToday(homeLat, homeLon, emptyList(), listOf(source))

        val todayRow = actuals[source]?.get(today)
        assertNotNull(todayRow)
        assertNotNull(
            "a full-day window must keep its observed low",
            todayRow!!.computedLowTemp,
        )
        assertTrue(
            "and it must be the overnight minimum, not an afternoon reading",
            todayRow.computedLowTemp!! < 60f,
        )
    }

    @Test
    fun `station-pull freeze on a past day still holds at the anchor site`() = runTest {
        val yesterday = today.minusDays(1)
        val yStart = yesterday.atStartOfDay(zone)
        fun yAt(h: Int) = yStart.plusHours(h.toLong()).toInstant().toEpochMilli()
        db.observationDao().insertAll(
            listOf(
                obs(homeLat, homeLon, yAt(2), 55f),
                obs(homeLat, homeLon, yAt(14), 70f),
            ),
        )
        db.dailyHistoryDao().insertAll(
            listOf(
                historyRow(homeLat, homeLon, high = 69.8f, low = 60.2f, actualsSource = DailyActualsSource.NWS_STATION_PULL.storedValue)
                    .copy(date = dateMillis(yesterday)),
            ),
        )

        store.recomputeDailyExtremesForDay(homeLat, homeLon, yesterday, emptyList())

        val row = db.dailyHistoryDao()
            .getExtremesInRange(dateMillis(yesterday), dateMillis(yesterday), homeLat, homeLon)
            .single { it.source == source }
        assertEquals("frozen station-pull blend must survive the site filter", 60.2f, row.computedLowTemp!!, 0.01f)
    }
}
