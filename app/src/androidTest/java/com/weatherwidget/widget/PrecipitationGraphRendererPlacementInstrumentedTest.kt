package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class PrecipitationGraphRendererPlacementInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun clusteredPeak_isPlacedAbove_whenDeclutterRuleApplies() {
        val placements = renderClusterScenario()

        val elevatedPeak =
            placements.firstOrNull {
                it.elevatedPeakRuleApplied &&
                    it.isPeak &&
                    it.probability in 60..85 &&
                    it.index in 18..21
            }

        assertNotNull(
            "Expected a clustered elevated peak placement near early-morning hump. Placements=$placements",
            elevatedPeak,
        )
        assertTrue(
            "Elevated peak should be placed above the curve. Placement=$elevatedPeak",
            elevatedPeak!!.placedAbove,
        )
    }

    @Test
    fun centerlineDip_isPlacedBelow_whenDipRuleApplies() {
        val placements = renderClusterScenario()

        val centeredDip =
            placements.firstOrNull {
                it.dipBelowRuleApplied &&
                    (it.isValley || it.isSoftDip) &&
                    it.probability <= 50
            }

        assertNotNull(
            "Expected a centerline dip placement with dip-below rule. Placements=$placements",
            centeredDip,
        )
        assertFalse(
            "Centerline dip should be placed below the curve. Placement=$centeredDip",
            centeredDip!!.placedAbove,
        )
    }

    @Test
    fun rightEdgeLabel_isPlacedBelow_whenDescendingNearCenter() {
        val placements = renderClusterScenario()

        val rightEdgeLabel =
            placements.firstOrNull {
                it.index >= 23 &&
                    it.probability in 45..55
            }

        assertNotNull(
            "Expected a right-edge mid-probability label near 47%. Placements=$placements",
            rightEdgeLabel,
        )
        assertFalse(
            "Right-edge descending-center label should be placed below the curve. Placement=$rightEdgeLabel",
            rightEdgeLabel!!.placedAbove,
        )
    }

    @Test
    fun rightEdgeLabel_isPlacedAbove_whenTrendingUpAndNotNearTop() {
        val start = LocalDateTime.of(2026, 2, 17, 10, 0)
        val probs =
            listOf(
                25, 28, 30, 32, 35, 36, 38, 40, 41, 42, 43, 44, 46,
                47, 48, 50, 52, 54, 56, 58, 60, 63, 67, 70, 73,
            )
        val hours =
            probs.mapIndexed { i, p ->
                val dt = start.plusHours(i.toLong())
                PrecipitationGraphRenderer.PrecipHourData(
                    dateTime = dt,
                    precipProbability = p,
                    label = formatHour(dt.hour),
                    isCurrentHour = false,
                    showLabel = false,
                )
            }

        val placements = mutableListOf<PrecipitationGraphRenderer.LabelPlacementDebug>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1100,
            heightPx = 400,
            currentTime = start,
            onLabelPlaced = { placements.add(it) },
        )

        val rightEdgeRisingLabel =
            placements.firstOrNull {
                it.index >= 23 &&
                    it.probability >= 68
            }

        assertNotNull(
            "Expected right-edge rising label near 70-73%. Placements=$placements",
            rightEdgeRisingLabel,
        )
        assertTrue(
            "Right-edge rising label should be placed above the curve. Placement=$rightEdgeRisingLabel",
            rightEdgeRisingLabel!!.placedAbove,
        )
    }

    @Test
    fun highRainPeak_isPlacedAbove_whenEnoughRoom() {
        val start = LocalDateTime.of(2026, 2, 17, 10, 0)
        // Longer peak plateau at 88% to resist double smoothing
        val probs = listOf(20, 20, 20, 88, 88, 88, 88, 88, 20, 20, 20)
        val hours = probs.mapIndexed { i, p ->
            val dt = start.plusHours(i.toLong())
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = dt,
                precipProbability = p,
                label = formatHour(dt.hour),
                isCurrentHour = false,
                showLabel = true,
            )
        }

        val placements = mutableListOf<PrecipitationGraphRenderer.LabelPlacementDebug>()
        // Tall bitmap (500px) provides plenty of room
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 500,
            currentTime = start,
            onLabelPlaced = { placements.add(it) },
        )

        val peak88 = placements.find { it.probability == 88 }
        assertNotNull("Expected 88% peak to be labeled. Placements=$placements", peak88)
        assertTrue("88% peak should be ABOVE if room exists. Placement=$peak88", peak88!!.placedAbove)
    }

    @Test
    fun highRainPeak_fallsBackBelow_whenNoRoomAbove() {
        val start = LocalDateTime.of(2026, 2, 17, 10, 0)
        // Longer peak plateau at 98%
        val probs = listOf(0, 0, 0, 98, 98, 98, 98, 98, 0, 0, 0)
        val hours = probs.mapIndexed { i, p ->
            val dt = start.plusHours(i.toLong())
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = dt,
                precipProbability = p,
                label = formatHour(dt.hour),
                isCurrentHour = false,
                showLabel = true,
            )
        }

        val placements = mutableListOf<PrecipitationGraphRenderer.LabelPlacementDebug>()
        // 150px height is still short enough to block ABOVE for a 98% peak,
        // but gives enough room for the BELOW fallback.
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 150,
            currentTime = start,
            onLabelPlaced = { placements.add(it) },
        )

        val peak98 = placements.find { it.probability == 98 }
        assertNotNull("Expected 98% peak to be labeled. Placements=$placements", peak98)
        // Should fall back to BELOW because top edge is too close
        assertFalse("98% peak should fall back BELOW if no room ABOVE. Placement=$peak98", peak98!!.placedAbove)
    }

    private fun renderClusterScenario(): List<PrecipitationGraphRenderer.LabelPlacementDebug> {
        val start = LocalDateTime.of(2026, 2, 17, 10, 0)
        val probs =
            listOf(
                99, 88, 58, 61, 65, 53, 90, 93, 80, 65, 80, 60, 59,
                55, 56, 40, 44, 44, 54, 69, 69, 59, 51, 49, 45,
            )
        val hours =
            probs.mapIndexed { i, p ->
                val dt = start.plusHours(i.toLong())
                PrecipitationGraphRenderer.PrecipHourData(
                    dateTime = dt,
                    precipProbability = p,
                    label = formatHour(dt.hour),
                    isCurrentHour = false,
                    showLabel = false,
                )
            }

        val placements = mutableListOf<PrecipitationGraphRenderer.LabelPlacementDebug>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1100,
            heightPx = 400,
            currentTime = start,
            onLabelPlaced = { placements.add(it) },
        )
        return placements
    }

    private fun formatHour(hour24: Int): String {
        val h = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        val suffix = if (hour24 < 12) "a" else "p"
        return "$h$suffix"
    }
}
