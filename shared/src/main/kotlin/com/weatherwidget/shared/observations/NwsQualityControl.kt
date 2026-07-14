package com.weatherwidget.shared.observations

/**
 * NWS publishes a per-field quality-control code alongside each observed value
 * (`properties.temperature.qualityControl`). We previously ignored it entirely and trusted any
 * value NWS returned — so a reading NWS itself had marked as failed or questionable entered the
 * blend at full weight.
 *
 * Codes are the MADIS/CDMS set NWS uses:
 *
 * | Code | Meaning                       | Usable |
 * |------|-------------------------------|--------|
 * | `V`  | Validated (passed QC)         | yes    |
 * | `C`  | Coarse pass                   | yes    |
 * | `S`  | Screened / subjective good    | yes    |
 * | `G`  | Good (subjective)             | yes    |
 * | `Z`  | Preliminary, no QC applied    | yes    |
 * | `X`  | Failed validation             | NO     |
 * | `Q`  | Questionable                  | NO     |
 * | `B`  | Subjective bad                | NO     |
 * | `T`  | Failed time-consistency check | NO     |
 *
 * `Z` is deliberately usable: it means "not yet QC'd", not "bad". Most real-time reports arrive as
 * `Z` and would otherwise all be discarded.
 *
 * This is NWS's own opinion of its data, independent of the Synoptic QC flags handled in
 * [SynopticApi][com.weatherwidget.data.remote.SynopticApi]. Both feed the same `qcFailed` column.
 */
object NwsQualityControl {

    private val FAILED_CODES = setOf("X", "Q", "B", "T")

    /** Null/blank (field absent) is treated as usable — absence of a code is not evidence of failure. */
    fun isFailed(qualityControl: String?): Boolean =
        qualityControl?.trim()?.uppercase()?.let { it in FAILED_CODES } ?: false
}
