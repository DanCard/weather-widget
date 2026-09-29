# NWS outside coverage: unavailable here, not removed from the user's list

Replaces the draft `260929-restore-nws-when-location-is-covered.md` (same day, never committed),
which kept the remove-and-restore model and only changed its restore rule.

## Report

2026-09-29 17:38, build 26092501, Pixel 7 Pro: "I switched the location to mountain view
California. NWS should have been enabled. The nws API should have been automatically enabled since
it was disabled automatically."

## Evidence

- Sep 3 bug reports list NWS among the sources; the Sep 24 report doesn't. The device log (from
  Sep 26) shows no removal, so NWS was removed before then.
- 17:27:13 `NWS_SETUP_CHECK lat=37.4166 result=supported reason=none sourceChange=none`: Mountain
  View, and NWS wasn't restored because `nws_auto_retired` was false.
- 17:39:00 `SOURCE_ORDER Checkbox: enabled NWS`: the user re-enabled it by hand.
- 17:42:14 → 17:42:41: Warsaw removed NWS (and set the marker), then Mountain View restored it. The
  mechanism works only when the marker was written.

## Root cause

Moving outside coverage **edits the user's source list**: `retireNwsOutsideCoverage` (worker),
`SetupSourcePolicy.sourcesAfterSetupCheck` (setup screen) and `withNwsCoverageApplied` (desktop). To
undo that edit later, the app needs a marker saying the removal was automatic. Any path that removes
NWS without the marker (most likely a build before `61086b37`, 2026-09-13), or clears it (every
Settings save, `WeatherSourcePreferences.setVisibleSources`), leaves NWS off for good. From then on
the app treats NWS as unticked by the user.

## Design

The user's enabled list records **only the user's choices**. Coverage is a fact about the current
location and is **derived every time it's needed, never stored**:

> effective sources = enabled sources, minus sources that don't cover the active location

- Coverage is `NwsCoverage.covers(lat, lon)`: instant, offline, and already what desktop uses. No
  location means no filtering.
- The effective list is never empty. If filtering removes everything, it's Open-Meteo, the rule
  `retireNws` has now. That fallback is **computed, not written** to the enabled list.
- Nothing is removed, so nothing needs restoring. Setup-screen changes, follow-device GPS moves and
  desktop picker saves all behave the same, with no code of their own.
- A user untick lasts until the user re-ticks. No marker, no guessing who removed it.

## Changes

### :shared
1. `NwsCoverage` → a `SourceCoverage.effectiveSources(enabledIds, lat, lon)` (location nullable) plus
   `supports(sourceId, lat, lon)`. Right now only NWS has a coverage rule; the function is written so
   another regional provider can be added later. Delete `retireNws` / `restoreNws` /
   `visibleSourcesFor`.

### Android
2. **One choke point.** `WeatherSourcePreferences.visibleSources()` returns the *effective* list,
   using `ActiveLocationResolver.current(context)`, which is a synchronous prefs read. That keeps
   every existing reader safe by default: the fetch coordinator, current-temp, snapshots, hourly,
   observation refreshers, header, toggle, stats, history, notifier (about 20 files). Add
   `enabledSources()` for the raw list, used **only** by Settings and by the enabled-list writers.
3. **The display-source fallback doesn't persist.** `currentDisplaySource` currently writes the
   decoded fallback back to prefs when the stored source isn't in the list. If the stored source is
   *enabled but unsupported here*, return the fallback without writing it, so a widget showing NWS
   shows Open-Meteo in Warsaw and NWS again in Mountain View. Same for `nextDisplaySource`
   (the toggle cycles through the effective list only) and `setCurrentDisplaySource`.
4. **Settings:** the checkbox list comes from `enabledSources()`. A source that's enabled but
   unsupported here stays checked, with the subtitle "Not available at this location" (new string ×
   20 locales, per the locale parity rule).
5. **Delete the removal/restore paths:**
   - `retireNwsOutsideCoverage` (`WeatherSourcePreferences`, `WidgetStateManager`, and the
     `FullSyncPipeline:90` heal);
   - the NWS half of `SetupSourcePolicy.sourcesAfterSetupCheck` and the `nws_auto_retired` marker
     (`isNwsAutoRetired` / `setNwsAutoRetired` / `KEY_NWS_AUTO_RETIRED`);
   - the marker bookkeeping in `ConfigActivity.applySetupSourceSelection`.
6. **The setup screen's live NWS `/points` probe goes.** Its only remaining use would be the NWS
   decision, which is now the coverage box. That removes about 1 s from every setup save
   (`elapsedMs≈900–1350` in today's logs). The WeatherAPI auto-add outside coverage stays, gated on
   `!covers(lat, lon)` instead of the probe result. It adds a source the user can untick, which is a
   separate question.
7. **One-time migration** (versioned pref, runs once): if NWS isn't in the enabled list, put it back
   in front. The app can't tell a lost-marker removal from a real untick, so this re-enables a
   deliberate untick once, which is the accepted trade-off. It then deletes the stale
   `nws_auto_retired` key. Log `NWS_ENABLED_MIGRATION outcome=restored|already_present`.

### Desktop
8. `DesktopSettings.visibleSources` stays the enabled list, as persisted. Add
   `effectiveSources(lat, lon)` using the shared function, and switch the readers (header cycle,
   popup, daemon runtime/process, history, observations, stats) to it. Settings keeps reading the
   raw list and adds the same "Not available at this location" note.
9. Delete `withNwsCoverageApplied`, both of its call sites (location-picker save,
   `DesktopConfig.kt:380` load) and the `nwsAutoRetired` field. JSON is `ignoreUnknownKeys = true`,
   so old configs still load. Same one-time migration as step 7, keyed on a config flag.

## Tests

- `:shared` `SourceCoverageTest`:
  - covered: identity;
  - uncovered: NWS filtered out, others kept;
  - an NWS-only list outside coverage falls back to Open-Meteo;
  - no location: unfiltered;
  - an untick (NWS absent) stays absent in coverage.
- Android Robolectric integration test (WeatherSourcePreferences + ActiveLocationResolver +
  LocationUpdater):
  - set Mountain View with a widget showing NWS, then Warsaw: that widget shows Open-Meteo and the
    stored selection still says NWS;
  - back to Mountain View: it shows NWS again;
  - the enabled list is unchanged throughout;
  - a follow-device move behaves the same.
- **Regression guard:** a test that runs a full sync with the active location in Warsaw and asserts
  the fetch coordinator is never asked for NWS. This catches any reader that bypasses the effective
  list and would 404 against `/points`.
- Migration test: an enabled list without NWS gets it restored once. The second run is a no-op, and
  an untick after the migration sticks.
- Rewrite `SetupSourceSelectorTest` and desktop `DesktopConfigSavePolicyTest` (`location picker
  round trip…`) for the new model. Their "list changes on a location change" assertions invert to
  "list never changes".
- On device (Pixel 7 Pro, currently NWS absent with `nws_auto_retired=true`):
  - install and check the migration log;
  - Warsaw via setup: NWS checked, "Not available at this location" in Settings, widgets on another
    source, no NWS fetch in `NET_FETCH_COMPLETE sources=`;
  - Mountain View: NWS back in the widget cycle with no user action.

## Docs

Update the CLAUDE.md "Weather Data APIs" bullet (enabled list vs effective list) and the
`NwsCoverage` KDoc that it replaces.
