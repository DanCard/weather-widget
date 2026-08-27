package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading

/**
 * An automated station's clear report says nothing about cloud above its ceilometer's ceiling.
 *
 * Observed 2026-08-27: the sky was near-overcast, KPAO reported `BKN180` — a broken deck at 18,000
 * ft — and KNUQ, the nearest station at 3.8 km, reported `CLR`. Inverse-square IDW handed KNUQ 69%
 * of the blend weight and the curve read 2%. KNUQ was not contradicting KPAO. An ASOS ceilometer is
 * a vertical laser with a hard 12,000 ft ceiling; it is physically incapable of seeing an 18,000 ft
 * deck, and the blend was counting that silence as a vote.
 *
 * The distinction is in the report itself, and our stored METARs reproduce the US convention
 * exactly: KNUQ is `AUTO` on 787 of 787 reports and says `CLR` 553 times, never `SKC`; KPAO carries
 * no `AUTO` tag on any of its 140 and says `SKC` 82 times, never `CLR`.
 *
 * - `CLR` — automated. "No cloud detected **below 12,000 ft**." Silent above that.
 * - `SKC` — human observer. "Sky clear." A whole-sky assessment, and trusted as one.
 *
 * **Deliberately not an outlier rule.** "Drop the 0 when others disagree" would also fire when KNUQ
 * says `CLR` and KPAO says `BKN008` — a deck at 800 ft, well inside KNUQ's range, where the
 * disagreement is real spatial variation and KNUQ's 0 is a true measurement. Discarding it there
 * would bias the curve upward on exactly the patchy-marine-layer mornings the graph most needs to
 * get right. Same symptom, opposite correct response. This rule is about what the instrument can
 * see, not about how much stations disagree.
 */
object CeilometerBlindSpot {

    /**
     * The ASOS ceilometer's reporting ceiling, 12,000 ft in metres. A layer at or below this is
     * inside every automated station's range, so a clear report genuinely contradicts it.
     */
    const val ASOS_CEILING_M = 3_658

    private val AUTOMATED_CLEAR = Regex("""\b(CLR|NCD)\b""")
    private val HUMAN_CLEAR = Regex("""\b(SKC|CAVOK)\b""")

    /**
     * Whether this reading is a clear report that cannot see above [ASOS_CEILING_M].
     *
     * Three classes, together covering every stored row (measured over 7 days of NWS rows):
     *  - `isMetar = false` — the `/observations` endpoint's 5-minute feed, an instantaneous
     *    ceilometer sample and automated by construction (3,799 rows). Parsing `rawMetar` alone
     *    would have classified only 239 of 498 clear rows; this class closes the gap.
     *  - a raw METAR carrying `CLR`/`NCD` — automated.
     *  - a raw METAR carrying `SKC`/`CAVOK` — a human observer, **trusted and never dropped** (113).
     *
     * A METAR we cannot classify (28 rows: `isMetar = true` with no stored raw) is trusted. Absence
     * of an `SKC` marker is not proof of automation, and discarding a real measurement on a guess is
     * worse than keeping a possibly-blind one.
     */
    fun isAutomatedClear(reading: ObservationReading, cover: Int?): Boolean {
        if (cover != 0) return false
        val raw = reading.rawMetar
        if (raw.isNullOrBlank()) return !reading.isMetar
        if (HUMAN_CLEAR.containsMatchIn(raw)) return false
        return AUTOMATED_CLEAR.containsMatchIn(raw)
    }

    /** The highest cloud base this reading actually reported, or null if it reported no base. */
    fun highestReportedBase(reading: ObservationReading): Int? = listOfNotNull(
        reading.cloudBaseLowMeters,
        reading.cloudBaseMidMeters,
        reading.cloudBaseHighMeters,
    ).maxOrNull()

    /**
     * Drops automated clear readings from one bucket's contributions when another reading in the
     * same bucket reports a layer above [ASOS_CEILING_M] — the layer the automated station could
     * not have seen.
     *
     * Returns the input unchanged when nothing qualifies, and **never returns empty**: if every
     * contribution would be dropped there is no better-informed station to defer to, and a degraded
     * answer beats no answer at all.
     */
    fun <T> filterBlindClears(
        contributions: List<T>,
        readingOf: (T) -> ObservationReading,
        coverOf: (T) -> Int?,
    ): List<T> {
        val aboveCeiling = contributions.any { entry ->
            val base = highestReportedBase(readingOf(entry)) ?: return@any false
            base > ASOS_CEILING_M && (coverOf(entry) ?: 0) > 0
        }
        if (!aboveCeiling) return contributions
        val kept = contributions.filterNot { isAutomatedClear(readingOf(it), coverOf(it)) }
        return kept.ifEmpty { contributions }
    }
}
