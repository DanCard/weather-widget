package com.weatherwidget.data.local

import androidx.room.Room
import com.weatherwidget.testutil.TestData.dateEpoch
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
        val d0302 = dateEpoch("2026-03-02")
        // Initial log
        dao.logCall(d0302, "OPEN_METEO")
        var usage = dao.getUsage(d0302, "OPEN_METEO")
        assertEquals(1, usage?.callCount)

        // Increment
        dao.logCall(d0302, "OPEN_METEO")
        usage = dao.getUsage(d0302, "OPEN_METEO")
        assertEquals(2, usage?.callCount)
    }

    @Test
    fun logCall_multipleDatesAndSources() = runTest {
        val d0302 = dateEpoch("2026-03-02")
        val d0303 = dateEpoch("2026-03-03")
        dao.logCall(d0302, "OPEN_METEO")
        dao.logCall(d0302, "NWS")
        dao.logCall(d0303, "OPEN_METEO")
        dao.logCall(d0303, "OPEN_METEO")

        assertEquals(1, dao.getUsage(d0302, "OPEN_METEO")?.callCount)
        assertEquals(1, dao.getUsage(d0302, "NWS")?.callCount)
        assertEquals(2, dao.getUsage(d0303, "OPEN_METEO")?.callCount)

        assertEquals(3, dao.getTotalUsage("OPEN_METEO"))
        assertEquals(1, dao.getTotalUsage("NWS"))
        assertEquals(null, dao.getTotalUsage("WEATHER_API"))
    }
}
