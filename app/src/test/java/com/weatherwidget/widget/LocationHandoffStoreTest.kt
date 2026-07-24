package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.SharedPreferencesUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
class LocationHandoffStoreTest : RobolectricTest() {

    private lateinit var context: Context
    private val active = 37.4168 to -122.0890

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit().clear().commit()
    }

    @Test
    fun `new site becomes candidate without changing active coordinates`() {
        val result = LocationHandoffStore.propose(
            context,
            active,
            HandoffLocation(37.3774, -122.0749, "Away"),
            nowMs = 100L,
        )

        assertEquals(CandidateProposal.UPDATED, result)
        assertEquals(37.3774, LocationHandoffStore.getCandidate(context)!!.location.lat, 0.0001)
        assertEquals(37.4168, active.first, 0.0001)
    }

    @Test
    fun `same candidate retains first seen time instead of restarting grace`() {
        LocationHandoffStore.propose(
            context,
            active,
            HandoffLocation(37.3774, -122.0749, "Away"),
            nowMs = 100L,
        )

        val result = LocationHandoffStore.propose(
            context,
            active,
            HandoffLocation(37.3775, -122.0748, "Away nearby"),
            nowMs = 500L,
        )

        assertEquals(CandidateProposal.SAME_CANDIDATE, result)
        assertEquals(100L, LocationHandoffStore.getCandidate(context)!!.firstSeenMs)
    }

    @Test
    fun `different moving site replaces candidate and restarts grace`() {
        LocationHandoffStore.propose(
            context,
            active,
            HandoffLocation(37.3774, -122.0749, "Away"),
            nowMs = 100L,
        )

        val result = LocationHandoffStore.propose(
            context,
            active,
            HandoffLocation(37.3318, -122.0312, "Farther away"),
            nowMs = 500L,
        )

        assertEquals(CandidateProposal.UPDATED, result)
        assertEquals(500L, LocationHandoffStore.getCandidate(context)!!.firstSeenMs)
    }

    @Test
    fun `returning to active site clears pending candidate`() {
        LocationHandoffStore.propose(
            context,
            active,
            HandoffLocation(37.3774, -122.0749, "Away"),
            nowMs = 100L,
        )

        val result = LocationHandoffStore.propose(
            context,
            active,
            HandoffLocation(37.4169, -122.0891, "Home"),
            nowMs = 500L,
        )

        assertEquals(CandidateProposal.RETURNED_TO_ACTIVE, result)
        assertNull(LocationHandoffStore.getCandidate(context))
    }

    @Test
    fun `promotion succeeds only for the candidate that was evaluated`() {
        LocationHandoffStore.propose(
            context,
            active,
            HandoffLocation(37.3774, -122.0749, "Away"),
            nowMs = 100L,
        )
        val evaluated = LocationHandoffStore.getCandidate(context)!!
        var promoted: HandoffLocation? = null

        assertTrue(LocationHandoffStore.promoteIfMatches(context, evaluated) { promoted = it })
        assertEquals("Away", promoted!!.label)
        assertNull(LocationHandoffStore.getCandidate(context))
    }
}
