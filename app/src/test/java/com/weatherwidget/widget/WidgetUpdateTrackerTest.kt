package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class WidgetUpdateTrackerTest {

    @Before
    @After
    fun cleanup() {
        WidgetUpdateTracker.clearForTesting()
    }

    @Test
    fun `cancelOtherJobs cancels background syncs and UI paints but keeps active widget`() {
        val job1 = Job()
        val job2 = Job()
        val job3 = Job()

        WidgetUpdateTracker.trackJob(101, job1, WidgetUpdateTracker.JobType.UI_PAINT)
        WidgetUpdateTracker.trackJob(102, job2, WidgetUpdateTracker.JobType.BACKGROUND_SYNC)
        WidgetUpdateTracker.trackJob(103, job3, WidgetUpdateTracker.JobType.UI_PAINT)

        val cancelled = WidgetUpdateTracker.cancelOtherJobs(103)

        assertEquals(listOf(101, 102).sorted(), cancelled.sorted())
        assertTrue("Job 101 should be cancelled", job1.isCancelled)
        assertTrue("Job 102 should be cancelled", job2.isCancelled)
        assertFalse("Job 103 should NOT be cancelled", job3.isCancelled)
        assertEquals(WidgetUpdateTracker.JobType.UI_PAINT, WidgetUpdateTracker.getActiveJobType(103))
        assertNull(WidgetUpdateTracker.getActiveJobType(101))
        assertNull(WidgetUpdateTracker.getActiveJobType(102))
    }

    @Test
    fun `cancelOtherJobs respects typesToCancel filter`() {
        val job1 = Job()
        val job2 = Job()

        WidgetUpdateTracker.trackJob(101, job1, WidgetUpdateTracker.JobType.UI_PAINT)
        WidgetUpdateTracker.trackJob(102, job2, WidgetUpdateTracker.JobType.INTERACTION)

        // Only cancel UI_PAINT, not INTERACTION
        val cancelled = WidgetUpdateTracker.cancelOtherJobs(
            activeAppWidgetId = 999,
            typesToCancel = setOf(WidgetUpdateTracker.JobType.UI_PAINT),
        )

        assertEquals(listOf(101), cancelled)
        assertTrue(job1.isCancelled)
        assertFalse(job2.isCancelled)
    }

    @Test
    fun `trackJob replace semantics - background sync does not cancel active UI paint`() {
        val uiJob = Job()
        val syncJob = Job()

        WidgetUpdateTracker.trackJob(101, uiJob, WidgetUpdateTracker.JobType.UI_PAINT)
        WidgetUpdateTracker.trackJob(101, syncJob, WidgetUpdateTracker.JobType.BACKGROUND_SYNC)

        assertFalse("UI paint should not be cancelled by sync", uiJob.isCancelled)
        assertEquals(WidgetUpdateTracker.JobType.UI_PAINT, WidgetUpdateTracker.getActiveJobType(101))
    }

    @Test
    fun `trackJob replace semantics - new UI paint cancels existing UI paint`() {
        val uiJob1 = Job()
        val uiJob2 = Job()

        WidgetUpdateTracker.trackJob(101, uiJob1, WidgetUpdateTracker.JobType.UI_PAINT)
        WidgetUpdateTracker.trackJob(101, uiJob2, WidgetUpdateTracker.JobType.UI_PAINT)

        assertTrue("Old UI paint should be cancelled", uiJob1.isCancelled)
        assertFalse("New UI paint should be active", uiJob2.isCancelled)
        assertEquals(WidgetUpdateTracker.JobType.UI_PAINT, WidgetUpdateTracker.getActiveJobType(101))
    }
}
