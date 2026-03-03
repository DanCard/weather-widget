package com.weatherwidget.data.local

import androidx.room.Room
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
class ApiUsageDaoTest {

    private lateinit var db: WeatherDatabase
    private lateinit var dao: ApiUsageDao

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.apiUsageDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun logCall_insertsNewRowAndIncrements() = runTest {
        // Initial log
        dao.logCall("2026-03-02", "OPEN_METEO")
        var usage = dao.getUsage("2026-03-02", "OPEN_METEO")
        assertEquals(1, usage?.callCount)

        // Increment
        dao.logCall("2026-03-02", "OPEN_METEO")
        usage = dao.getUsage("2026-03-02", "OPEN_METEO")
        assertEquals(2, usage?.callCount)
    }

    @Test
    fun logCall_multipleDatesAndSources() = runTest {
        dao.logCall("2026-03-02", "OPEN_METEO")
        dao.logCall("2026-03-02", "NWS")
        dao.logCall("2026-03-03", "OPEN_METEO")
        dao.logCall("2026-03-03", "OPEN_METEO")

        assertEquals(1, dao.getUsage("2026-03-02", "OPEN_METEO")?.callCount)
        assertEquals(1, dao.getUsage("2026-03-02", "NWS")?.callCount)
        assertEquals(2, dao.getUsage("2026-03-03", "OPEN_METEO")?.callCount)

        assertEquals(3, dao.getTotalUsage("OPEN_METEO"))
        assertEquals(1, dao.getTotalUsage("NWS"))
        assertEquals(null, dao.getTotalUsage("WEATHER_API"))
    }
}
