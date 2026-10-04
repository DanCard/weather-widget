package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.NwsApi

/**
 * The stored `stationType` strings and the one rule for which of them the blend trusts less.
 *
 * Anything that asks "is this an official station?" compares against [OFFICIAL]; anything that asks
 * "does the personal-station discount apply?" must use [isDiscounted], never `== PERSONAL`, so that
 * [RAWS] is treated identically to personal stations without every caller knowing it exists.
 */
object StationTypes {
    val OFFICIAL = NwsApi.StationType.OFFICIAL.name
    val PERSONAL = NwsApi.StationType.PERSONAL.name
    val RAWS = NwsApi.StationType.RAWS.name

    /** True for station types that get the personal-station discount and thinning. */
    fun isDiscounted(stationType: String?): Boolean = stationType == PERSONAL || stationType == RAWS
}
