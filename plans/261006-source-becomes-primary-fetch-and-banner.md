# A source that becomes primary: fetch at once, and say so while it loads

## Problem
When a source becomes primary with no data for it yet (Google enabled in Settings; the debug
migration after an install), the widget shows that source's empty graph — today..+2 blank, climate
averages after — until some unrelated sync fetches it:
- **Pixel, 2026-10-06:** ~80 s (install 11:24:38 → data 11:26:31).
- **emulator-5556:** ~40 s (11:38:41 → 11:39:20), 29.8 s of it `SYNC_DEFERRED_STARTUP`.
- **Android Settings enable fetches nothing at all** (`SettingsActivity` only calls
  `setVisibleSourcesOrder`); the switch waits for the next periodic/opportunistic sync.

## Design (user: "do both")
1. **Fetch immediately when a source becomes primary from Settings** — an expedited (API 31+),
   forced, source-targeted sync, built like `LocationUpdater.buildForceRefreshRequest`. The process
   is warm (Settings is open), so the startup cooldown is not in force.
2. **Banner "Getting weather from {source}…"** over the widget from the switch until that sync ends —
   the same mechanism as the location-change banner (user's 2026-09-28 call), shown only when the
   new primary has no drawable cache (today's row + today's hourly, `LocationChangePaintPolicy`-style).
   Cleared on sync end; on failure cleared, and the source's existing warning/"Tap to refresh" path
   takes over. 120 s safety cap as today.

### Generalise, don't copy
- `LocationChangeBanner` → a message-keyed `FetchBanner` (show/clear by text); location-change and
  source-switch both use it. Worker input gains `KEY_SOURCE_SWITCH_ID`; `FullSyncPipeline` clears the
  banner at the same three exits it already handles for location changes.
- "Did a source just become primary?" is already shared: `WeatherSourceOrdering.selectionAfterChange`
  / `PRIMARY_ON_ENABLE`. Add `WeatherSourceOrdering.newlyPrimary(oldIds, newIds): WeatherSource?`
  in `:shared`, used by Android Settings, the debug migration, and desktop Settings.

### Desktop
Desktop rebuilds its service when `weatherSource` changes and refreshes on launch; it will show
its existing `LocationBanner` with the source message until that refresh ends, when the new source
has no cached daily row for today. (Verify the refresh is immediate; if not, trigger it.)

## Open decision: the post-install case
The debug migration runs at process start, inside the startup cooldown. Fetching immediately there
means overriding the cooldown that fixed the measured post-install tap storm
(`performance/260910-post-install-cold-start-storm.md`: taps drained one per 8 s for over a minute).
**Recommended:** keep the cooldown there; show the banner at once so the ~30 s wait is explained.
The migration is one-time per debug install; Settings enables (every other case) fetch immediately.

## Tests
- `:shared`: `newlyPrimary` cases.
- Android (Robolectric): Settings enable of Google → expedited forced work enqueued with
  `KEY_SOURCE_SWITCH_ID=GOOGLE_WEATHER`; banner text set on every widget; enabling OWM (appended,
  not primary) → no banner, no forced work; source with drawable cache → no banner.
- `FullSyncPipeline`: banner cleared on success, empty, failure, exception (mirrors the location
  tests); location-change banner behaviour unchanged.
- Desktop: source switch with no cache → banner state on until refresh completes.
- Emulator: enable Google in Settings on a running app → banner appears, data within a few seconds.
