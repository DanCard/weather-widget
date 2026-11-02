package com.weatherwidget.desktop

/**
 * Whether a finished load may reach the popup.
 *
 * A fetch outlives the screen and the repository it started on ([com.weatherwidget.shared.util.UserRefreshRunner]).
 * Its rows are good, but its snapshot belongs to that repository: published after a source switch it
 * replaced the selected source's graph, and [DesktopSourceWait] then blanked the popup with
 * "Loading Google Weather…" until something else reloaded (2026-11-02).
 * plans/261102-desktop-late-refresh-overwrites-selected-source.md
 *
 * Compared by identity, not source id: the repository is rebuilt for a new location, source or API key,
 * so a location change during a refresh is caught too.
 */
internal object DesktopSnapshotPublish {
    const val DROP_TAG = "STALE_PUBLISH_DROPPED"

    fun isCurrent(owner: Any?, current: Any?): Boolean = owner != null && owner === current

    fun dropLine(ownerSource: String, currentSource: String?, origin: String): String =
        "owner=$ownerSource current=$currentSource origin=$origin"
}
