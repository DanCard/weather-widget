package com.weatherwidget.shared.sourceview

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * How likely the user is to switch sources today, and to view a given source — from the last
 * [LOOKBACK_DAYS] of `source_view_days`. ONE estimator for Android and desktop; nothing acts on it yet
 * (a later fetch-policy plan will). See plans/261010-source-view-tracking-table.md.
 *
 * Recency-weighted Beta-Binomial over **calendar days** (user, 2026-10-10): a day the phone sat in a
 * drawer counts as a day nothing was viewed, because the question is whether anyone will look before
 * the next fetch, not how likely a switch is while the user is engaged.
 *
 * ```
 * w(age) = 0.5 ^ (age / HALF_LIFE_DAYS)       age 1 = yesterday; today is excluded until it ends
 * n      = Σ w over counted days               (from yesterday back to LOOKBACK_DAYS or trackingSince)
 * k      = Σ w over counted days with the event
 * p      = (k + α) / (n + α + β)
 * ```
 *
 * With H = 7 the weights of 30 days sum to ~9.1, so a user who never switches reads ~9 %, not 0 —
 * any threshold built on this must sit below that floor.
 */
object SourceViewProbability {
    const val HALF_LIFE_DAYS = 7.0
    const val PRIOR_ALPHA = 1.0
    const val PRIOR_BETA = 1.0
    const val LOOKBACK_DAYS = com.weatherwidget.data.local.RetentionPolicy.SOURCE_VIEW_DAYS

    data class Estimate(
        /** Posterior mean. */
        val probability: Double,
        /** 90th percentile of the posterior: act on this when cutting back, so thin data never cuts off. */
        val upper90: Double,
        /** n: the summed day weights. */
        val effectiveDays: Double,
        /** k: the summed weights of days with the event. */
        val weightedEvents: Double,
        /** Calendar days counted, unweighted. */
        val countedDays: Int,
    )

    /**
     * Q1: the chance the user switches to a non-primary source today, by the API button or the
     * Observations screen. The home button only returns to the primary, so it is not a toggle.
     */
    fun toggleToday(rows: List<SourceViewDayRow>, trackingSince: LocalDate?, today: LocalDate): Estimate {
        val eventDays = rows.filter {
            it.switches > 0 && !it.wasPrimary &&
                (it.trigger == SourceViewTrigger.TOGGLE.name || it.trigger == SourceViewTrigger.OBSERVATIONS.name)
        }.mapTo(HashSet()) { it.date }
        return estimate(eventDays, trackingSince, today)
    }

    /**
     * Q2: the chance [sourceId] is viewed today; [viewKind] null means any view. The current primary
     * ([primarySourceId]) is what is shown, so it is 1.
     */
    fun sourceViewedToday(
        sourceId: String,
        rows: List<SourceViewDayRow>,
        trackingSince: LocalDate?,
        today: LocalDate,
        primarySourceId: String?,
        viewKind: SourceViewKind? = null,
    ): Estimate {
        if (sourceId == primarySourceId) {
            val base = estimate(emptySet(), trackingSince, today)
            return base.copy(probability = 1.0, upper90 = 1.0, weightedEvents = base.effectiveDays)
        }
        val eventDays = rows.filter {
            it.sourceId == sourceId && it.switches > 0 && (viewKind == null || it.viewKind == viewKind.name)
        }.mapTo(HashSet()) { it.date }
        return estimate(eventDays, trackingSince, today)
    }

    /** A per-day probability stretched to [hours], e.g. a source's fetch interval. */
    fun withinHours(pDay: Double, hours: Double): Double = 1.0 - (1.0 - pDay).pow(hours / 24.0)

    /** Once per local day: due when nothing was logged yet, or the last line is from an earlier day. */
    fun isDailyLogDue(lastLoggedMs: Long?, nowMs: Long, zone: java.time.ZoneId): Boolean =
        lastLoggedMs == null || SourceViewTally.dayMs(lastLoggedMs, zone) < SourceViewTally.dayMs(nowMs, zone)

    /** `SOURCE_VIEW_PROBABILITY` app_logs line: `toggle=0.16 NWS=1.00 OPEN_METEO=0.12 …`. */
    fun summaryLine(
        rows: List<SourceViewDayRow>,
        trackingSince: LocalDate?,
        today: LocalDate,
        primarySourceId: String?,
        sourceIds: List<String>,
    ): String {
        val toggle = toggleToday(rows, trackingSince, today)
        val perSource = sourceIds.joinToString(" ") { id ->
            "$id=${fmt(sourceViewedToday(id, rows, trackingSince, today, primarySourceId).probability)}"
        }
        return "toggle=${fmt(toggle.probability)} upper90=${fmt(toggle.upper90)} " +
            "days=${toggle.countedDays} $perSource".trimEnd()
    }

    private fun fmt(p: Double) = String.format(java.util.Locale.US, "%.2f", p)

    private fun estimate(eventDays: Set<LocalDate>, trackingSince: LocalDate?, today: LocalDate): Estimate {
        var n = 0.0
        var k = 0.0
        var counted = 0
        val start = trackingSince ?: today
        for (age in 1..LOOKBACK_DAYS) {
            val day = today.minusDays(age)
            if (day.isBefore(start)) break
            val w = 0.5.pow(age / HALF_LIFE_DAYS)
            n += w
            counted++
            if (day in eventDays) k += w
        }
        val a = k + PRIOR_ALPHA
        val b = (n - k) + PRIOR_BETA
        return Estimate(
            probability = a / (a + b),
            upper90 = BetaQuantile.quantile(0.9, a, b),
            effectiveDays = n,
            weightedEvents = k,
            countedDays = counted,
        )
    }
}

/** Beta-distribution quantile by bisection on the regularized incomplete beta (Numerical Recipes 6.4). */
internal object BetaQuantile {
    fun quantile(q: Double, a: Double, b: Double): Double {
        var lo = 0.0
        var hi = 1.0
        repeat(60) {
            val mid = (lo + hi) / 2
            if (regularizedIncompleteBeta(mid, a, b) < q) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    fun regularizedIncompleteBeta(x: Double, a: Double, b: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val front = exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * ln(x) + b * ln(1 - x))
        return if (x < (a + 1) / (a + b + 2)) {
            front * continuedFraction(x, a, b) / a
        } else {
            1.0 - front * continuedFraction(1 - x, b, a) / b
        }
    }

    private fun continuedFraction(x: Double, a: Double, b: Double): Double {
        val tiny = 1e-300
        var c = 1.0
        var d = 1.0 - (a + b) * x / (a + 1)
        if (abs(d) < tiny) d = tiny
        d = 1.0 / d
        var h = d
        for (m in 1..300) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2))
            d = 1.0 + aa * d
            if (abs(d) < tiny) d = tiny
            c = 1.0 + aa / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            h *= d * c
            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1))
            d = 1.0 + aa * d
            if (abs(d) < tiny) d = tiny
            c = 1.0 + aa / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            val del = d * c
            h *= del
            if (abs(del - 1.0) < 1e-12) break
        }
        return h
    }

    /** Lanczos approximation (g = 7, n = 9). */
    private fun lnGamma(x: Double): Double {
        if (x < 0.5) return ln(Math.PI / kotlin.math.sin(Math.PI * x)) - lnGamma(1 - x)
        val g = 7.0
        val coef = doubleArrayOf(
            0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
            1.5056327351493116e-7,
        )
        val xx = x - 1
        var sum = coef[0]
        for (i in 1 until coef.size) sum += coef[i] / (xx + i)
        val t = xx + g + 0.5
        return 0.5 * ln(2 * Math.PI) + (xx + 0.5) * ln(t) - t + ln(sum)
    }
}
