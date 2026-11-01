package com.weatherwidget.desktop

/**
 * What the popup shows in place of its graphs while it has nothing of the selected source's to draw.
 *
 * A click moves config at once and the snapshot follows when its load returns, so the previous source's
 * graph stays up under the new label. That is fine for the usual half second (844 switches: median
 * 472 ms, slowest 2871 ms) and wrong if it lasts: a source with no saved rows loads as null, which left
 * one source's forecast under another's name for as long as the fetch took, or for good if it failed.
 * summaries/261101-desktop-source-switch-graph-flash-recap.md
 */
internal enum class DesktopSourceWait {
    /** The load is slow, or the source has no rows yet and its fetch is still running. */
    LOADING,

    /** The source has no rows here and its fetch has ended. */
    NO_DATA,
    ;

    fun message(sourceName: String): String = when (this) {
        LOADING -> "Loading $sourceName…"
        NO_DATA -> "No $sourceName data for this location"
    }

    companion object {
        /** Longer than any switch measured, so a normal switch never shows a message. */
        const val SLOW_LOAD_MS = 3_000L

        const val LOG_TAG = "SOURCE_SWITCH_WAIT"

        /**
         * @param snapshotIsSelectedSource false while the previous source's snapshot is still held.
         * @param loadIsSlow that has lasted [SLOW_LOAD_MS].
         * @param snapshotIsEmpty the selected source's load came back with no rows.
         * @param fetchEndedEmpty the daemon has reported since then, and the rows are still not there.
         * @return null when the snapshot should be drawn.
         */
        fun resolve(
            snapshotIsSelectedSource: Boolean,
            loadIsSlow: Boolean,
            snapshotIsEmpty: Boolean,
            fetchEndedEmpty: Boolean,
        ): DesktopSourceWait? = when {
            !snapshotIsSelectedSource -> if (loadIsSlow) LOADING else null
            !snapshotIsEmpty -> null
            fetchEndedEmpty -> NO_DATA
            else -> LOADING
        }
    }
}
