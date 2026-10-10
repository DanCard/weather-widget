package com.weatherwidget.data.model

/**
 * What kind of station an observation came from. One enum for every feed (NWS, METAR, Synoptic) and
 * both platforms; it used to travel as a free string, and a renderer comparing the wrong spelling
 * ("OFFICIAL" against the Blend tab's "O") failed silently.
 *
 * Stored as [dbCode] in `observations.stationType` (INTEGER) on Android Room and desktop SQLite:
 *
 * | code | type |
 * |---|---|
 * | 0 | UNKNOWN |
 * | 1 | OFFICIAL |
 * | 2 | PERSONAL |
 * | 3 | RAWS |
 * | 4 | BLENDED (in-memory blend row only; never stored) |
 *
 * [dbCode] is an explicit persistence contract, like [CloudVerticalKind.dbCode]. Never persist
 * [ordinal]: reordering entries must not reinterpret stored rows. `StationTypeTest` pins the codes.
 * See plans/261009-station-type-enum-integer-codes-in-db.md.
 */
enum class StationType(
    val dbCode: Int,
    /** Single-letter code for the Blend tab's type column, explained by its legend. */
    val label: String,
) {
    UNKNOWN(0, "?"),
    OFFICIAL(1, "O"),
    PERSONAL(2, "P"),

    /**
     * Remote Automated Weather Stations (Synoptic `MNET_ID` 2): fire-weather sites, passive
     * radiation shields, often on ridges. Kept distinct for provenance, but discounted and thinned
     * exactly like [PERSONAL] — see [isDiscounted]. Label `F` (fire): `R` already means "real
     * reading" in the Blend tab's value column.
     */
    RAWS(3, "F"),

    /**
     * The synthetic `NWS_BLEND` row (`NwsBlend`). Built in memory on read on both platforms and
     * never stored (plans/261009-desktop-stops-storing-nws-blend.md).
     */
    BLENDED(4, "B"),
    ;

    /**
     * True for station types that get the personal-station discount and thinning. Anything asking
     * "does the personal discount apply?" must use this, never `== PERSONAL`, so [RAWS] is treated
     * identically without every caller knowing it exists.
     */
    val isDiscounted: Boolean get() = this == PERSONAL || this == RAWS

    companion object {
        /** Unknown codes (a newer build's value) read as [UNKNOWN] rather than failing the read. */
        fun fromDbCode(dbCode: Int): StationType = entries.firstOrNull { it.dbCode == dbCode } ?: UNKNOWN

        /** For text sources (the station-list cache); unknown names read as [UNKNOWN]. */
        fun fromName(name: String?): StationType = entries.firstOrNull { it.name == name } ?: UNKNOWN

        /**
         * SQL expression mapping the old TEXT column to [dbCode], shared by the Room v75→v76 and
         * desktop v28→v29 migrations so both platforms convert identically. `VIRTUAL` was a legacy
         * name for the blend row; both blend spellings are excluded by the migrations, which drop
         * `NWS_BLEND` rows, but map here too so nothing is left as UNKNOWN by accident.
         */
        val SQL_CODE_FROM_LEGACY_NAME: String =
            "CASE stationType " +
                entries.filter { it != UNKNOWN }.joinToString(" ") { "WHEN '${it.name}' THEN ${it.dbCode}" } +
                " WHEN 'VIRTUAL' THEN ${BLENDED.dbCode} ELSE ${UNKNOWN.dbCode} END"
    }
}
