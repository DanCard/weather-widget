package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TemperatureLabelResolverSortTest {

    @Test
    fun `sortLabelCandidates prioritizes HIGH over LOCAL regardless of temperature`() {
        val labelTemps = listOf(20f, 25f, 20f)
        val candidates = mutableListOf(
            TempLabelCandidate(1, TemperatureRole.LOCAL, labelTemps, 25f, false), // 25 deg (Peak)
            TempLabelCandidate(0, TemperatureRole.HIGH, labelTemps, 20f, false)    // 20 deg (High)
        )

        // HIGH (priority 0) should come before LOCAL (priority 1)
        TemperatureLabelResolver.sortLabelCandidates(candidates)

        assertEquals(TemperatureRole.HIGH, candidates[0].role)
        assertEquals(TemperatureRole.LOCAL, candidates[1].role)
    }

    @Test
    fun `sortLabelCandidates prioritizes LOCAL over END regardless of temperature`() {
        val labelTemps = listOf(10f, 20f, 30f)
        val candidates = mutableListOf(
            TempLabelCandidate(2, TemperatureRole.END, labelTemps, 30f, false),   // 30 deg (End)
            TempLabelCandidate(1, TemperatureRole.LOCAL, labelTemps, 20f, false)  // 20 deg (Local)
        )

        // LOCAL (priority 1) should come before END (priority 2)
        TemperatureLabelResolver.sortLabelCandidates(candidates)

        assertEquals(TemperatureRole.LOCAL, candidates[0].role)
        assertEquals(TemperatureRole.END, candidates[1].role)
    }

    @Test
    fun `sortLabelCandidates sorts HIGH roles by descending temperature`() {
        val labelTemps = listOf(30f, 25f, 20f)
        val candidates = mutableListOf(
            TempLabelCandidate(1, TemperatureRole.HIGH, labelTemps, 25f, false),
            TempLabelCandidate(0, TemperatureRole.HIGH, labelTemps, 30f, false)
        )

        TemperatureLabelResolver.sortLabelCandidates(candidates)

        assertEquals(30f, candidates[0].labelTemps[candidates[0].index])
        assertEquals(25f, candidates[1].labelTemps[candidates[1].index])
    }

    @Test
    fun `LOCAL extrema are in the essential roles set`() {
        assertTrue(TemperatureRole.LOCAL in TemperatureLabelResolver.ESSENTIAL_LABEL_ROLES)
    }

    @Test
    fun `sortLabelCandidates sorts LOCAL peaks by descending temperature`() {
        val labelTemps = listOf(10f, 30f, 20f, 40f, 15f)
        val candidates = mutableListOf(
            TempLabelCandidate(1, TemperatureRole.LOCAL, labelTemps, 30f, false), // Peak 30
            TempLabelCandidate(3, TemperatureRole.LOCAL, labelTemps, 40f, false)  // Peak 40
        )

        TemperatureLabelResolver.sortLabelCandidates(candidates)

        assertEquals(40f, candidates[0].labelTemps[candidates[0].index])
        assertEquals(30f, candidates[1].labelTemps[candidates[1].index])
    }
}
