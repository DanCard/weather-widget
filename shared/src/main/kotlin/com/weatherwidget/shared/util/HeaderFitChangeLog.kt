package com.weatherwidget.shared.util

/**
 * Decides when a [HeaderFit.LOG_TAG] line is worth persisting: the first plan a header shows in
 * this process, then only when its header buttons change (shown/hidden, count, home, months).
 *
 * Every render resolves a plan, and resizes, recompositions and ticks repeat the same one, so
 * logging each would flood the persistent log. A button change is what a "header buttons are
 * missing" report needs; the persisted line carries the widths that explain it. Text-only changes
 * (date, rain %, caption) and button-zone narrowing stay out.
 *
 * Shared by Android (keyed per widget and view) and desktop (keyed per view).
 */
class HeaderFitChangeLog {
    private val last = HashMap<String, String>()

    /** True when [plan]'s buttons differ from the last plan recorded for [key] (or none was). */
    @Synchronized
    fun changed(key: String, plan: HeaderFit.Plan): Boolean {
        val signature = buttonSignature(plan)
        if (last[key] == signature) return false
        last[key] = signature
        return true
    }

    companion object {
        fun buttonSignature(plan: HeaderFit.Plan): String =
            "${plan.placement} buttons=${plan.buttons} home=${plan.homeShown} months=${plan.monthsShown}"
    }
}
