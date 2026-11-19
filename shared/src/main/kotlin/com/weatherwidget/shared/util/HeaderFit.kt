package com.weatherwidget.shared.util

/**
 * What a header row shows when it does not all fit — one rule for Android and desktop.
 *
 * Priority, highest first: source label + gear (always; the caller's [Measured.rightCluster]),
 * current temperature, rain %, delta, action buttons (base buttons, then home, then months),
 * weather icon, date, "from yest". Items are offered room in that order and each is kept only if
 * the row still fits with it, so a higher item never loses room to a lower one; a lower item may
 * still take room that a higher one could not use.
 *
 * "from yest" never shows beside a rain %.
 *
 * **Button placement (right-of-center):** Action buttons are right-anchored directly to the left of
 * the right cluster (`buttonsRight = width - rightCluster - gap`) and grow leftward into the open
 * corridor. Because [HeaderButtons.ORDER] puts `HOME` first (leftmost) and `HISTORY` last
 * (rightmost), adding/removing `HOME` or switching daily ↔ hourly leaves the rightmost buttons
 * pinned. When the corridor is narrower than the preferred button width, buttons narrow evenly down
 * to [Measured.minButtonWidth]. The weather icon may narrow them; the date and "from yest" never do.
 *
 * **Date placement (daily only):** Centered in the remaining free space to the left of the buttons
 * (`[leftCluster + gap, buttonsLeft - gap]`), or dropped if it does not fit.
 *
 * Pure: widths come in already measured, in any one unit (Android px, desktop dp), each including
 * its own leading margin. Each platform measures and draws; this only decides.
 */
object HeaderFit {
    const val LOG_TAG = "HEADER_FIT"

    /**
     * The [LOG_TAG] line, both platforms: view, width (dp), header scale, the plan and what it was
     * resolved from. [measured] is in each platform's own units (Android px, desktop dp).
     */
    fun logLine(view: String, widthDp: Float, scale: Float, plan: Plan, measured: Measured): String =
        "view=$view widthDp=${"%.0f".format(widthDp)} scale=${"%.2f".format(scale)} $plan $measured"

    /** Whether action buttons are shown (right-anchored before the right cluster) or hidden. */
    enum class ButtonPlacement { SHOWN, HIDDEN }

    data class Measured(
        val width: Float,
        /** Source label and gear pinned at the right edge. Always shown. */
        val rightCluster: Float,
        /** Minimum horizontal space between adjacent header clusters. */
        val gap: Float,
        val temp: Float = 0f,
        val rain: Float = 0f,
        val delta: Float = 0f,
        val icon: Float = 0f,
        val date: Float = 0f,
        val caption: Float = 0f,
        /** Buttons that are always wanted (observations, history, etc.); home and months come on top. */
        val baseButtons: Int = 0,
        val wantHome: Boolean = false,
        val wantMonths: Boolean = false,
        /** Preferred width of one action button; [buttonSpacing] between neighbours. */
        val buttonWidth: Float = 0f,
        /** Narrowest an action button may be squeezed to; 0 = not at all. */
        val minButtonWidth: Float = 0f,
        val buttonSpacing: Float = 0f,
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
        /** Resolved width of one action button (<= [Measured.buttonWidth] when narrowed); 0 when hidden. */
        val buttonZone: Float = 0f,
    )

    fun resolve(m: Measured): Plan {
        var plan = Plan()
        fun offer(candidate: Plan, narrowButtons: Boolean = true): Boolean =
            fitted(m, candidate, narrowButtons)?.also { plan = it } != null

        if (m.temp > 0f) offer(plan.copy(showTemp = true))
        if (m.rain > 0f) offer(plan.copy(showRain = true))
        if (m.delta > 0f) offer(plan.copy(showDelta = true))
        offerButtons(m, plan)?.let { plan = it }
        if (m.icon > 0f) offer(plan.copy(showIcon = true))
        if (m.date > 0f) offer(plan.copy(showDate = true), narrowButtons = false)
        if (m.caption > 0f && plan.showDelta && !plan.showRain) {
            offer(plan.copy(showCaption = true), narrowButtons = false)
        }
        return plan
    }

    private fun offerButtons(m: Measured, from: Plan): Plan? {
        if (m.baseButtons <= 0 || m.buttonWidth <= 0f) return null
        var placed = fitted(
            m,
            from.copy(buttons = m.baseButtons, placement = ButtonPlacement.SHOWN, buttonZone = 0f),
        ) ?: return null
        if (m.wantHome) {
            fitted(m, placed.copy(buttons = placed.buttons + 1))?.let {
                placed = it.copy(homeShown = true)
            }
        }
        if (m.wantMonths) {
            fitted(m, placed.copy(buttons = placed.buttons + 1))?.let {
                placed = it.copy(monthsShown = true)
            }
        }
        return placed
    }

    /** Whether [plan] fits the row as [m] measures it, narrowing buttons down to [Measured.minButtonWidth] if allowed. */
    fun fits(m: Measured, plan: Plan): Boolean = fitted(m, plan) != null

    private fun fitted(m: Measured, plan: Plan, narrowButtons: Boolean = true): Plan? {
        val left = leftClusterWidth(m, plan)
        val rightEdge = m.width - m.rightCluster
        if (left > 0f && left + m.gap > rightEdge) return null

        val corridorLeft = if (left > 0f) left + m.gap else 0f
        val corridorRight = rightEdge - m.gap
        val corridor = corridorRight - corridorLeft

        if (plan.placement != ButtonPlacement.SHOWN || plan.buttons <= 0) {
            if (plan.showDate && m.date > 0f) {
                val dLeft = (m.width - m.date) / 2f
                if (dLeft < corridorLeft || dLeft + m.date > corridorRight) return null
            }
            return plan.copy(buttonZone = 0f)
        }

        val ceiling = zoneWidth(m, plan)
        if (ceiling <= 0f) return null
        val spacing = (plan.buttons - 1) * m.buttonSpacing
        val buttonsMinLeft = if (plan.showDate && m.date > 0f) {
            val dLeft = (m.width - m.date) / 2f
            if (dLeft < corridorLeft) return null
            maxOf(corridorLeft, dLeft + m.date + m.gap)
        } else {
            corridorLeft
        }
        val roomForZones = corridorRight - buttonsMinLeft - spacing
        val maxPerButton = roomForZones / plan.buttons

        if (maxPerButton >= ceiling) return plan.copy(buttonZone = ceiling)
        if (!narrowButtons) return null
        val floor = if (m.minButtonWidth > 0f) minOf(m.minButtonWidth, ceiling) else ceiling
        return if (maxPerButton >= floor) plan.copy(buttonZone = maxPerButton) else null
    }

    private fun zoneWidth(m: Measured, plan: Plan): Float =
        plan.buttonZone.takeIf { it > 0f } ?: m.buttonWidth

    /**
     * Left and right edges of the action buttons as [plan] lays them out (right-anchored before
     * [Measured.rightCluster]); null when hidden.
     */
    fun buttonsSpan(m: Measured, plan: Plan): Pair<Float, Float>? {
        if (plan.placement != ButtonPlacement.SHOWN || plan.buttons <= 0) return null
        val width = plan.buttons * zoneWidth(m, plan) + (plan.buttons - 1) * m.buttonSpacing
        val right = m.width - m.rightCluster - m.gap
        return (right - width) to right
    }

    /**
     * Left edge of the date as [plan] lays it out, or null when not shown.
     * Pinned to the widget's horizontal center so toggling API sources or adding/removing buttons
     * never shifts the date; dropped if it does not fit cleanly between the left cluster and buttons.
     */
    fun dateLeft(m: Measured, plan: Plan): Float? {
        if (!plan.showDate || m.date <= 0f) return null
        return (m.width - m.date) / 2f
    }

    private fun leftClusterWidth(m: Measured, plan: Plan): Float =
        (if (plan.showIcon) m.icon else 0f) +
            (if (plan.showTemp) m.temp else 0f) +
            (if (plan.showDelta) m.delta else 0f) +
            (if (plan.showCaption) m.caption else 0f) +
            (if (plan.showRain) m.rain else 0f)
}
