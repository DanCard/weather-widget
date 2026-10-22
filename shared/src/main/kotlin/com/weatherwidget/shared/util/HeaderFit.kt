package com.weatherwidget.shared.util

/**
 * What a header row shows when it does not all fit — one rule for Android and desktop
 * (`plans/261022-shared-header-fit-priority.md`).
 *
 * Priority, highest first (user, 2026-10-22): source label (always; the caller's right cluster),
 * current temperature, rain %, delta, buttons (home, then months, are the buttons' first to go),
 * weather icon, date, "from yest". Items are offered room in that order and each is kept only if
 * the row still fits with it, so a higher item never loses room to a lower one; a lower item may
 * still take room that a higher one could not use (a short date beside buttons that had to drop).
 *
 * "from yest" never shows beside a rain % (Android's rule since 2026-08; desktop adopted it
 * 2026-10-22).
 *
 * The centred buttons end on a fixed line right of the middle ([Measured.seamOffset]; the left side
 * is the crowded one) and the date starts just after it. Buttons are added to the line's LEFT, so
 * home appearing on a source switch, or the observations button going when today is panned away,
 * never moves the date (user, 2026-10-22: it jumped on every source tap, and on the Fold the home
 * button pushed it out). Where the buttons do not fit there they try the true middle, as before,
 * then INLINE.
 * Android's painted date never shares the row with INLINE buttons: it is painted into the bitmap,
 * which cannot see the RemoteViews button row, and it was painted straight over them (emulator,
 * 360dp, 2026-10-21). Desktop has no INLINE rung.
 *
 * INLINE buttons compress before anything drops (Android's hourly header, user 2026-10-09): the
 * zones narrow toward [Measured.minInlineButton], then the weather icon and temperature shrink
 * together toward [Measured.minTextScale]; "from yest" is kept only where it needs neither.
 *
 * Pure: widths come in already measured, in any one unit (Android px, desktop dp), each including
 * its own leading margin. Each platform measures and draws; this only decides.
 */
object HeaderFit {
    /**
     * How far right of the middle the buttons end, in dp before any header scaling. Measured
     * 2026-10-22: the 360dp emulator widget needs ≥ 65 for three buttons beside its weather icon;
     * the Fold's 662dp widget (×1.35) leaves the date room beside home up to 93.
     */
    const val SEAM_OFFSET_DP = 70f

    /** Where the buttons sit: centred (on a [Seam]), appended to the left cluster, or not at all. */
    enum class ButtonPlacement { CENTER, INLINE, HIDDEN }

    /** Which line CENTER buttons hang off. */
    enum class Seam {
        /** Ending on the fixed line [Measured.seamOffset] right of the middle; the date after it. */
        RIGHT_OF_MIDDLE,

        /** Centred on the middle — the fallback, and the only spot where the line cannot be used. */
        MIDDLE,
    }

    data class Measured(
        val width: Float,
        /** Source label and anything pinned beside it (desktop's gear). Always shown. */
        val rightCluster: Float,
        /** Minimum space between the clusters, and between the buttons and the date. */
        val gap: Float,
        /**
         * How far right of the middle the buttons end ([SEAM_OFFSET_DP], scaled); null where the
         * platform cannot move them off the middle (Android < 12).
         */
        val seamOffset: Float? = null,
        val temp: Float = 0f,
        val rain: Float = 0f,
        val delta: Float = 0f,
        val icon: Float = 0f,
        val date: Float = 0f,
        val caption: Float = 0f,
        /** Buttons that are always wanted (observations, history); home and months come on top. */
        val baseButtons: Int = 0,
        val wantHome: Boolean = false,
        val wantMonths: Boolean = false,
        /** One centred button; [centreButtonSpacing] between neighbours. */
        val centreButton: Float = 0f,
        val centreButtonSpacing: Float = 0f,
        /** One inline button zone; 0 means there is no INLINE rung (desktop). */
        val inlineButton: Float = 0f,
        /** Margin before the first inline zone. */
        val inlineLead: Float = 0f,
        /** Narrowest an inline zone may be squeezed to; 0 = not at all. */
        val minInlineButton: Float = 0f,
        /** Smallest scale for the weather icon and temperature beside INLINE buttons; 1 = none. */
        val minTextScale: Float = 1f,
    )

    data class Plan(
        val showTemp: Boolean = false,
        val showRain: Boolean = false,
        val showDelta: Boolean = false,
        val showIcon: Boolean = false,
        val showDate: Boolean = false,
        val showCaption: Boolean = false,
        /** Total buttons shown, home and months included. */
        val buttons: Int = 0,
        val homeShown: Boolean = false,
        val monthsShown: Boolean = false,
        val placement: ButtonPlacement = ButtonPlacement.HIDDEN,
        /** Which line CENTER buttons hang off. */
        val seam: Seam = Seam.RIGHT_OF_MIDDLE,
        /** The inline zone width used (≤ [Measured.inlineButton] when squeezed); 0 unless INLINE. */
        val inlineZone: Float = 0f,
        /** Scale for the weather icon and temperature (< 1 only when squeezed beside INLINE buttons). */
        val textScale: Float = 1f,
    )

    fun resolve(m: Measured): Plan {
        var plan = Plan(seam = if (m.seamOffset != null) Seam.RIGHT_OF_MIDDLE else Seam.MIDDLE)
        fun offer(candidate: Plan): Boolean = fits(m, candidate).also { if (it) plan = candidate }

        if (m.temp > 0f) offer(plan.copy(showTemp = true))
        if (m.rain > 0f) offer(plan.copy(showRain = true))
        if (m.delta > 0f) offer(plan.copy(showDelta = true))
        offerButtons(m, plan)?.let { plan = it }
        if (m.icon > 0f) offer(plan.copy(showIcon = true))
        if (m.date > 0f) offer(plan.copy(showDate = true))
        if (m.caption > 0f && plan.showDelta && !plan.showRain) {
            // Only where it fits as laid out: it never narrows the zones or shrinks the temperature.
            val withCaption = plan.copy(showCaption = true)
            if (squeeze(m, withCaption) == squeeze(m, plan)) offer(withCaption)
        }
        val (zone, scale) = squeeze(m, plan) ?: (0f to 1f)
        return plan.copy(inlineZone = zone, textScale = scale)
    }

    /**
     * The base buttons on the best rung that fits — the line right of the middle, the middle,
     * INLINE; then home, which may step the row down a rung (every button still visible); then
     * months, on the same rung or not at all.
     */
    private fun offerButtons(m: Measured, from: Plan): Plan? {
        if (m.baseButtons <= 0) return null
        val rungs = buildList {
            if (m.seamOffset != null) add(ButtonPlacement.CENTER to Seam.RIGHT_OF_MIDDLE)
            add(ButtonPlacement.CENTER to Seam.MIDDLE)
            if (m.inlineButton > 0f) add(ButtonPlacement.INLINE to from.seam)
        }
        fun place(count: Int, rungsToTry: List<Pair<ButtonPlacement, Seam>>, base: Plan): Plan? =
            rungsToTry.map { (placement, seam) -> base.copy(buttons = count, placement = placement, seam = seam) }
                .firstOrNull { fits(m, it) }

        var placed = place(m.baseButtons, rungs, from) ?: return null
        val rung = rungs.indexOf(placed.placement to placed.seam)
        if (m.wantHome) {
            place(placed.buttons + 1, rungs.drop(rung), placed)?.let { placed = it.copy(homeShown = true) }
        }
        if (m.wantMonths) {
            val sameRung = listOf(placed.placement to placed.seam)
            place(placed.buttons + 1, sameRung, placed)?.let { placed = it.copy(monthsShown = true) }
        }
        return placed
    }

    /** Whether [plan] fits the row as [m] measures it, squeezing INLINE buttons if it must. */
    fun fits(m: Measured, plan: Plan): Boolean = squeeze(m, plan) != null

    /**
     * The inline zone width and icon/temperature scale [plan] needs, nominal first (0 zone when not
     * INLINE); null when it does not fit even fully squeezed.
     */
    private fun squeeze(m: Measured, plan: Plan): Pair<Float, Float>? {
        val inline = plan.placement == ButtonPlacement.INLINE && plan.buttons > 0
        if (!inline) return if (fitsAsLaidOut(m, plan)) 0f to 1f else null
        if (plan.showDate && m.date > 0f) return null
        val available = m.width - m.rightCluster - m.gap
        val scalable = (if (plan.showIcon) m.icon else 0f) + (if (plan.showTemp) m.temp else 0f)
        val fixed = leftClusterWidth(m, plan.copy(showIcon = false, showTemp = false, placement = ButtonPlacement.HIDDEN)) + m.inlineLead
        val zoneRoom = (available - fixed - scalable) / plan.buttons
        if (zoneRoom >= m.inlineButton) return m.inlineButton to 1f
        val minZone = if (m.minInlineButton > 0f) minOf(m.minInlineButton, m.inlineButton) else m.inlineButton
        if (zoneRoom >= minZone) return zoneRoom to 1f
        if (scalable <= 0f || m.minTextScale >= 1f) return null
        val scale = (available - fixed - plan.buttons * minZone) / scalable
        return if (scale >= m.minTextScale) minZone to scale else null
    }

    private fun fitsAsLaidOut(m: Measured, plan: Plan): Boolean {
        val left = leftClusterWidth(m, plan)
        val rightEdge = m.width - m.rightCluster
        if (left > 0f && left + m.gap > rightEdge) return false
        buttonsSpan(m, plan)?.let { (slotLeft, slotRight) ->
            if (slotLeft < left + m.gap || slotRight > rightEdge - m.gap) return false
        }
        val dateLeft = dateLeft(m, plan) ?: return true
        return dateLeft >= left + m.gap && dateLeft + m.date <= rightEdge - m.gap
    }

    /** Left and right edges of the CENTER buttons as [plan] lays them out; null when there are none. */
    fun buttonsSpan(m: Measured, plan: Plan): Pair<Float, Float>? {
        if (plan.placement != ButtonPlacement.CENTER || plan.buttons <= 0) return null
        val width = plan.buttons * m.centreButton + (plan.buttons - 1) * m.centreButtonSpacing
        val middle = m.width / 2f
        return when (plan.seam) {
            Seam.RIGHT_OF_MIDDLE -> (middle + (m.seamOffset ?: 0f)).let { right -> right - width to right }
            Seam.MIDDLE -> middle - width / 2f to middle + width / 2f
        }
    }

    /**
     * Left edge of the date as [plan] lays it out, or null when it is not shown. After the CENTER
     * buttons; without them, at the line where they would end — pushed right of a long left
     * cluster rather than dropped, since nothing then competes for that spot.
     */
    fun dateLeft(m: Measured, plan: Plan): Float? {
        if (!plan.showDate || m.date <= 0f) return null
        buttonsSpan(m, plan)?.let { (_, slotRight) -> return slotRight + m.gap }
        val line = m.width / 2f + (m.seamOffset ?: 0f) + m.gap
        return maxOf(line, leftClusterWidth(m, plan) + m.gap)
    }

    private fun leftClusterWidth(m: Measured, plan: Plan): Float =
        (if (plan.showIcon) m.icon else 0f) +
            (if (plan.showTemp) m.temp else 0f) +
            (if (plan.showDelta) m.delta else 0f) +
            (if (plan.showCaption) m.caption else 0f) +
            (if (plan.showRain) m.rain else 0f) +
            (if (plan.placement == ButtonPlacement.INLINE && plan.buttons > 0) m.inlineLead + plan.buttons * m.inlineButton else 0f)
}
