package com.weatherwidget.data.local

import androidx.room.Room
import com.weatherwidget.data.repository.ForecastRepository
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * app_logs keeps widget breadcrumbs on a budget separate from routine telemetry.
 *
 * Regression cover for 2026-07-22: a single whole-table cap of 50k, against ~32k rows/day of
 * telemetry, trimmed app_logs to 38h even though the age policy is 72h — and took the WIDGET_PUSH
 * history for the failure window with it, leaving no record of what stranded a widget on the bare
 * widget_weather layout.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class AppLogTieredRetentionTest {

    private lateinit var db: WeatherDatabase
    private lateinit var dao: AppLogDao

    private val protectedTags = ForecastRepository.APP_LOG_PROTECTED_TAGS

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.appLogDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private suspend fun insert(tag: String, count: Int) {
        repeat(count) { dao.insert(AppLogEntity(tag = tag, message = "$tag-$it")) }
    }

    private suspend fun countOf(tag: String): Int =
        dao.getRecentLogs(Int.MAX_VALUE).count { it.tag == tag }

    @Test
    fun `telemetry flood cannot evict widget breadcrumbs`() = runTest {
        // Breadcrumbs first, so they are the OLDEST rows — under a single whole-table cap ordered
        // by id they are exactly what gets deleted.
        insert("WIDGET_PUSH", 10)
        insert("WIDGET_PAINT", 10)
        insert("CURR_TEMP_RESULT", 2_000)

        dao.capUnprotectedToNewest(keep = 100, protectedTags = protectedTags)

        assertEquals("WIDGET_PUSH rows must survive a telemetry flood", 10, countOf("WIDGET_PUSH"))
        assertEquals("WIDGET_PAINT rows must survive a telemetry flood", 10, countOf("WIDGET_PAINT"))
        // Guards the assertions above from passing vacuously: the cap really did run.
        assertEquals("Unprotected rows must be trimmed to the cap", 100, countOf("CURR_TEMP_RESULT"))
    }

    @Test
    fun `the old whole-table cap would have evicted those breadcrumbs`() = runTest {
        insert("WIDGET_PUSH", 10)
        insert("WIDGET_PAINT", 10)
        insert("CURR_TEMP_RESULT", 2_000)

        // An empty protected list makes capUnprotectedToNewest equivalent to the single
        // whole-table cap this change replaced. Demonstrates the protection above is what saves the
        // breadcrumbs, not the choice of `keep` or the insert order.
        dao.capUnprotectedToNewest(keep = 100, protectedTags = emptyList())

        assertEquals("Old behaviour: breadcrumbs are the oldest rows, so they go first", 0, countOf("WIDGET_PUSH"))
        assertEquals(0, countOf("WIDGET_PAINT"))
    }

    @Test
    fun `protected tags are still bounded by their own cap`() = runTest {
        insert("WIDGET_PUSH", 500)

        dao.capProtectedToNewest(keep = 100, protectedTags = protectedTags)

        assertEquals("A runaway protected tag must still be bounded", 100, countOf("WIDGET_PUSH"))
    }

    @Test
    fun `capping one class leaves the other untouched`() = runTest {
        insert("WIDGET_PUSH", 50)
        insert("CURR_TEMP_RESULT", 50)

        dao.capProtectedToNewest(keep = 10, protectedTags = protectedTags)

        assertEquals(10, countOf("WIDGET_PUSH"))
        assertEquals("Protected cap must not touch unprotected rows", 50, countOf("CURR_TEMP_RESULT"))
    }

    @Test
    fun `newest rows are the ones kept`() = runTest {
        insert("CURR_TEMP_RESULT", 50)

        dao.capUnprotectedToNewest(keep = 5, protectedTags = protectedTags)

        val survivors = dao.getRecentLogs(Int.MAX_VALUE).map { it.message }
        assertTrue(
            "Expected the newest 5 rows, got $survivors",
            survivors.containsAll(
                listOf(
                    "CURR_TEMP_RESULT-45",
                    "CURR_TEMP_RESULT-46",
                    "CURR_TEMP_RESULT-47",
                    "CURR_TEMP_RESULT-48",
                    "CURR_TEMP_RESULT-49",
                ),
            ),
        )
    }
}
