package com.weatherwidget.widget

import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource

/**
 * "Has the observation data actually changed since the last graph render?", as a single comparable
 * number, for [GraphRepaintGate].
 *
 * The gate used to answer that question with the formatted current-temp string, which is a *proxy*:
 * it is blind to everything else drawn on the graph bitmap — the dominant-station label, its
 * `@ 4:35` reading time, the observed dot. A new reading that leaves the temp string unchanged
 * therefore left a stale `knuq 71.6 @ 4:35` on screen for up to
 * [GraphRepaintGate.MAX_BITMAP_INTERVAL_MS]. See
 * `plans/260818-widget-repaint-gate-data-watermark.md`.
 *
 * ### Why `timestamp` and not `fetchedAt`
 *
 * `fetchedAt` looks like the obvious "when did the DB last change" marker and is the wrong one.
 * It carries **attempt** semantics: `INSERT OR REPLACE` refreshes it whenever any storable
 * observation comes back — *even a byte-identical repeat of one already stored* — and
 * `touchLatestFetchedAt` bumps it on a definitively empty attempt so the Observations screen can
 * tell a broken station (Reported old / Fetched fresh) from a broken pipeline (both old). Keying
 * the gate on it would advance the watermark on essentially every fetch cycle regardless of whether
 * anything drawn changed, which is blind periodic repainting wearing a new name.
 *
 * `timestamp` moves when — and only when — a station publishes a genuinely newer reading, which is
 * exactly what the label renders. It is also what the blend, extrema and retention paths key on, so
 * the gate agrees with the pipeline it is gating instead of drifting from it.
 *
 * Known gap, accepted: a *correction* to an existing row (same `timestamp`, revised temperature)
 * does not move the watermark. [GraphRepaintGate.MAX_BITMAP_INTERVAL_MS] remains the backstop.
 */
object ObservationWatermark {

    /** Returned when there is nothing to measure. Never forces a rebuild — see [GraphRepaintGate]. */
    const val NONE = 0L

    /**
     * The newest observation time among the [rows] that [displaySourceId] actually draws, or [NONE]
     * when there are none.
     *
     * [rows] arrive already scoped to the widget's location but carry **every** source — one fetch
     * cycle refreshes NWS, Open-Meteo, Silurian and Tomorrow.io together. An unscoped max would
     * therefore tick whenever any non-displayed source published a newer reading, rebuilding a
     * bitmap that did not change and undoing the point of the gate. The predicate mirrors
     * [ObservationResolver.resolveObservedCurrentTemp] exactly, so the watermark measures the same
     * rows the display selects from; if that filter changes, this must change with it.
     */
    fun of(rows: List<ObservationEntity>, displaySourceId: String): Long {
        if (!WeatherSource.fromId(displaySourceId).supportsTemperatureActuals) return NONE
        return rows.asSequence()
            .filter { it.api == displaySourceId || it.api == WeatherSource.GENERIC_GAP.id }
            .maxOfOrNull { it.timestamp }
            ?: NONE
    }
}
