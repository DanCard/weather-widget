# Graceful location handoff and widget-body recovery

**Date:** 2026-07-24  
**Status:** Implemented and verified  
**Scope:** Android widget behavior when the phone moves between locations and later returns.

## Product principle

Prefer a complete, slightly old display over a current-location display that is nearly blank.

A cached location is not automatically bad or invalid. While the phone is moving, every fix and
weather response is old by some amount. The widget does not need navigation-grade location
freshness; it needs a stable, useful weather display with modest battery and CPU use.

The prior plan treated a location change as a hard boundary and proposed rejecting any render from
the prior location. That was unnecessarily strict. This revision instead treats a new location as a
candidate. The currently displayed location stays active until the candidate has enough weather
data to produce a useful body.

## Implementation result

1. Passive GPS samples now update a persisted candidate rather than immediately replacing the
   active per-widget coordinates.
2. A candidate promotes immediately with three usable daily days plus near-complete visible hourly
   coverage. A genuinely new site with current/future hourly coverage promotes after a 30-minute
   movement grace so missing pre-arrival history cannot block it forever.
3. Candidate refresh work is unique and uses `ExistingWorkPolicy.KEEP`; it coalesces requests and
   never cancels an in-flight worker.
4. A candidate that is not yet useful produces no RemoteViews delivery, leaving the last complete
   active-location body on the launcher.
5. The first complete-body delivery after a 15-minute body-delivery gap is promoted to one full
   rebind. Header-only updates remain partial and do not reset the body timer.
6. Pure JVM, Robolectric launcher/cache, real-Room instrumented home-away-home, duration-bucket,
   and debug APK build checks pass. The instrumented transition test passed on both connected
   emulators without inspecting or modifying either physical phone.

## Observed failures

### Daily body at XML defaults

The Samsung launcher has previously shown a populated header over the unbound
`widget_weather.xml` body (`Today`, `--°`, `--°`, and no graph). The existing
`LauncherCacheDropRecoveryTest` reproduces a launcher losing its hosted tree, but its
`KNOWN DEFECT` test currently asserts that repeated partial updates remain discarded.

This is a RemoteViews delivery/rebinding issue. It is separate from which location supplies the
weather data.

### Hourly graph with only the right/future portion

During the July 23 location transition, intermediate data loads had large gaps in the visible NWS
forecast window and were still allowed to repaint the widget. A later fetch restored the complete
graph.

The test added afterward verifies gap classification and diagnostics, not the user-facing handoff.
It does not require the previous complete graph to remain visible while candidate-location data is
being fetched.

## Goals

1. Keep the last useful widget body visible while weather for a newly observed location is being
   fetched and evaluated.
2. Switch the displayed location only when the candidate can render a useful body.
3. Avoid rapid display and network churn while the phone is moving.
4. Restore a launcher body that has fallen back to XML defaults without making routine worker
   updates full or visibly flashy.
5. Add deterministic tests for the complete movement sequence: home → away → home.
6. Continue using the existing passive cached location. Do not add active GPS polling.
7. Continue using sparse diagnostics rather than persistent per-render traces.

## Non-goals

1. Do not require every location fix or weather row to be perfectly current.
2. Do not suppress a useful prior-location display merely because a newer fix exists.
3. Do not invent historical forecasts for a location where the app never collected them.
4. Do not combine hourly rows from physically different sites into one apparently continuous graph.
5. Do not fetch on every movement or location sample.
6. Do not force every complete-body RemoteViews update to be full.
7. Do not simulate a real drive, Android doze, or Samsung launcher internals in the automated suite.

## Proposed location-handoff model

Maintain two concepts:

1. **Active display location** — the location whose last useful weather body remains on screen.
2. **Candidate location** — the latest materially different passive location fix for which data is
   being obtained.

They may be the same during normal operation. While moving, the candidate may change while the
active display location remains stable.

### Handoff flow

1. `GpsResampler` reads the same passive fused `lastLocation` it uses today. No extra location
   request is introduced.
2. If the fix is within the existing same-site tolerance, do nothing.
3. If it is materially different, store/update the candidate location without immediately
   discarding the active display location or its last good body.
4. Schedule at most one normal candidate-data refresh through the existing location-refresh path.
   Candidate changes while that work is pending are coalesced rather than starting a fetch for
   every fix.
5. After data is loaded, evaluate whether the candidate can render a useful version of the widget's
   current view.
6. If it is useful, promote the candidate to active and render it.
7. If it is not useful, keep displaying the active location and retry through the existing refresh
   cadence or the already-bounded missing-data refresh. Do not blank or partially overwrite the
   body.
8. When the phone returns to a previously visited location, its cached history may make it useful
   immediately; promote it as soon as that cached data passes the same usability check.

This is a display handoff policy, not a promise that the displayed weather always follows every
moment of a drive.

## Define “useful,” not “perfect”

Add a pure, view-specific `RenderUsability` decision. The exact thresholds must be derived from
existing provider horizons and the observed near-blank fixtures before production constants are
chosen.

The initial contract should be:

### Daily graph/text

1. The body has real forecast content rather than XML placeholders.
2. Today and a meaningful forward range contain usable high/low data for the selected source or the
   existing supported fallback.
3. The graph renderer can produce a non-null bitmap for graph mode.

### Hourly temperature

1. The current/center portion and a meaningful forward forecast range are populated for the
   selected source.
2. A large edge-connected gap that would leave the visible forecast graph nearly blank is not
   considered useful during candidate handoff.
3. Missing historical forecast anchors at a genuinely new site do not block promotion forever.
   Once a successful provider response supplies the expected current/future horizon, that can be a
   useful new-location graph even if forecast history from before arrival does not exist.
4. Returning home should normally restore the cached home history and the fuller graph before the
   handoff.

### New widget with no prior body

If there is no last useful body to retain, show an intentional loading/data-pending body. Do not
leave the raw XML defaults as the user-visible fallback.

## Battery and CPU policy

1. Keep passive `lastLocation`; do not request active GPS fixes in the background.
2. Do not increase the periodic worker frequency.
3. Coalesce multiple candidate fixes into one pending candidate.
4. Do not render a new graph bitmap for every fix while moving.
5. Reuse the existing candidate refresh and missing-data cooldowns.
6. Promote only after data already loaded by a normal or already-requested refresh passes the
   usability decision.
7. Keep ordinary complete-body worker delivery partial.
8. A launcher-recovery full push is a local RemoteViews rebind, not an additional weather fetch.

## Launcher-body recovery

Keep the launcher problem separate from location selection.

`WidgetPushDispatcher` already promotes the first complete-body partial in a new app process. It
does not recover if the launcher loses its tree while the app process remains alive.

Add a conservative idle-gap rule:

1. Track the elapsed time of the last complete-body delivery per widget.
2. If complete-body delivery has been silent beyond a measured threshold, promote the next
   complete-body partial to one full update.
3. After that full succeeds, return to partial delivery.
4. Header-only updates remain partial and do not count as complete-body delivery.
5. Verify with a fake monotonic clock that normal active sequences gain no additional full pushes.

This gives the launcher one complete body after an idle period while avoiding continuous full
redraws. The initial threshold should not be chosen by intuition alone; compare it with existing
complete-body intervals and the known outage before fixing the value in tests.

## Implementation phases

### Phase 1: turn the field scenarios into failing tests

1. Add a reusable two-location fixture:
   1. home has complete daily and hourly cached data;
   2. away initially has only sparse/current-future data;
   3. away later receives a successful provider response; and
   4. home is selected again with its prior cache intact.
2. Add a pure handoff test:
   1. home is active;
   2. away becomes candidate;
   3. sparse away data fails usability;
   4. home remains active;
   5. usable away data promotes away;
   6. home later becomes candidate; and
   7. cached useful home data promotes home without a blank intermediate state.
3. Add movement-churn cases:
   1. A → B → C fixes arrive while B data is pending;
   2. only the latest candidate remains pending;
   3. the active display remains A until a candidate becomes useful; and
   4. no test expects a fetch or render for every fix.
4. Add a Robolectric RemoteViews test that starts with a bound complete graph and proves an
   unusable candidate cannot replace it with a nearly empty bitmap.
5. Add a no-prior-body case proving the app sends an intentional loading/data-pending body instead
   of leaving `widget_weather.xml` defaults.
6. Update `LauncherCacheDropRecoveryTest` with a fake elapsed clock. After an idle gap and simulated
   host-tree loss, the next complete-body push must rebind the graph immediately.
7. Demonstrate that the new assertions fail against current production behavior before implementing
   the handoff and idle-gap changes.

### Phase 2: separate candidate location from active display location

1. Introduce a small Android-side location-handoff state holder backed by the existing widget
   preferences.
2. Preserve compatibility:
   1. existing configured coordinates become the active location on upgrade;
   2. no candidate means current behavior; and
   3. fixed/pinned mode continues to bypass GPS handoff.
3. Change GPS auto-heal to propose a candidate instead of immediately replacing the active display
   location.
4. Keep manual location selection explicit:
   1. a user-confirmed fixed location may become active immediately when useful cached data exists;
   2. otherwise retain the current body while the selected location loads; and
   3. never silently fall back to follow-device mode.
5. Route candidate fetches using candidate coordinates while ordinary display/render paths continue
   reading active coordinates.
6. After a successful data load, run `RenderUsability` for each placed widget's current view.
7. Promote candidate to active when the required views are useful. If widgets use different views,
   choose one documented promotion rule—preferably all placed widgets can render a non-placeholder
   body—rather than allowing widget locations to diverge accidentally.
8. Record sparse handoff state changes:
   1. `candidate_detected`;
   2. `candidate_waiting_data`;
   3. `candidate_promoted`; and
   4. `candidate_superseded`.

High-frequency location samples stay at VERBOSE if logged at all. Persist only the state changes
above.

### Phase 3: protect the last useful body

1. Add `RenderUsability` immediately before choosing whether candidate data may replace the active
   body.
2. When candidate data is not useful:
   1. keep the active location and its body;
   2. avoid delivering the incomplete candidate bitmap;
   3. retain the existing `TEMP_GAPS_REFRESH` request/cooldown when applicable; and
   4. do not call this an error if the phone is simply moving.
3. Once candidate data is useful:
   1. promote it;
   2. render one complete body from the new active location; and
   3. resume normal partial worker paints.
4. Do not mix active-location daily data with candidate-location hourly data in one delivery.
5. Keep a small last-success marker per widget/view so first-bind/loading behavior can be
   distinguished from “retain the body already on screen.”

### Phase 4: add bounded launcher rebind

1. Add an injectable monotonic clock to the dispatcher policy seam.
2. Track last complete-body delivery per widget.
3. Promote one complete body after the tested idle threshold.
4. Keep header-only updates partial across any gap.
5. Include `promoted=idle_gap` and the measured gap in the sparse full-push breadcrumb.
6. Reset dispatcher timing state for deleted widget IDs.

## Work scheduling safety note

This is not part of the display policy or a theory about the failure.

The existing location path already enqueues fresh work. Prefer that request rather than adding a
second recovery worker. If implementation evidence shows an additional deduplicated request is
necessary, it must not cancel a running `WeatherWidgetWorker`; use the project's established
non-cancelling policy. Ideally this plan makes no WorkManager policy change.

## Test layers

### Pure JVM tests

1. Candidate/active state transitions.
2. Same-site fixes do not create candidates.
3. Candidate supersession while moving.
4. Daily and hourly usability decisions.
5. A genuinely new location can become useful without fabricated history.
6. Returning to a cached location can promote immediately.
7. Idle-gap full-promotion policy with a fake clock.
8. Header-only and active below-threshold delivery remains partial.

### Robolectric tests

1. A complete existing graph remains bound while candidate data is inadequate.
2. Candidate promotion produces a real handler-built RemoteViews body.
3. A new widget with no prior body shows an intentional loading state.
4. Fake-launcher cache loss after idle is repaired by exactly one full body.
5. View toggles during candidate loading keep a meaningful body.

### Instrumented test

Add one Room/AppWidgetHost-backed location-handoff test:

1. bind a synthetic widget;
2. seed complete home data and sparse away data;
3. make away the candidate through the production handoff path;
4. verify the bound home graph remains visible;
5. insert the successful away response and promote it;
6. verify a real away bitmap is hosted;
7. make home the candidate again;
8. verify cached home data promotes without an XML-default or near-blank intermediate frame; and
9. verify refresh requests are bounded rather than emitted for every simulated fix.

Provide Robolectric counterparts for the contracts that do not require real Room, AppWidgetHost, or
bitmap application.

## Likely files

Production seams:

1. `app/src/main/java/com/weatherwidget/widget/GpsResampler.kt`
2. `app/src/main/java/com/weatherwidget/ui/LocationUpdater.kt`
3. `app/src/main/java/com/weatherwidget/widget/ActiveLocationResolver.kt`
4. `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`
5. `app/src/main/java/com/weatherwidget/widget/WidgetRenderer.kt`
6. `app/src/main/java/com/weatherwidget/widget/WidgetPushDispatcher.kt`
7. `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt`

Tests:

1. `app/src/test/java/com/weatherwidget/widget/GpsResamplerTest.kt`
2. `app/src/test/java/com/weatherwidget/ui/LocationUpdaterTest.kt`
3. `app/src/test/java/com/weatherwidget/widget/LauncherCacheDropRecoveryTest.kt`
4. `app/src/test/java/com/weatherwidget/widget/WidgetPushDispatcherTest.kt`
5. `app/src/test/java/com/weatherwidget/widget/handlers/MissingForecastHoursTest.kt`
6. new `LocationHandoffTest`
7. new `LocationHandoffRoboTest`
8. new `LocationHandoffIntegrationTest`

Final names should follow the actual extracted seams. Every JVM test class receives exactly one
measured duration/category bucket.

## Verification

### Focused JVM

Run the new handoff, usability, GPS, dispatcher, and missing-hours tests together:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.weatherwidget.widget.LocationHandoffTest \
  --tests com.weatherwidget.widget.LocationHandoffRoboTest \
  --tests com.weatherwidget.widget.GpsResamplerTest \
  --tests com.weatherwidget.widget.LauncherCacheDropRecoveryTest \
  --tests com.weatherwidget.widget.WidgetPushDispatcherTest \
  --tests com.weatherwidget.widget.handlers.MissingForecastHoursTest
```

Then run the relevant duration buckets and category validation.

### Emulator

Run serially:

```bash
./scripts/emulator-tests.sh \
  -c com.weatherwidget.widget.LocationHandoffIntegrationTest
```

Leave the emulator running. Do not run another same-package instrumented installation concurrently.

Capture controlled screenshots for:

1. complete home;
2. sparse away candidate while home remains displayed;
3. usable away after promotion; and
4. cached home after returning.

Confirm there is no XML-default or near-blank intermediate body.

### Broader checks

1. Run all app unit-test duration buckets.
2. Build with `./gradlew assembleDebug`.
3. Run existing add/bind/render and view-toggle instrumented tests serially.
4. Exercise daily/hourly toggles, resize, user refresh, ordinary worker partial updates, and a
   controlled idle-gap launcher rebind.
5. Confirm movement simulation does not increase passive location reads, worker frequency, or
   refresh requests in proportion to the number of fixes.
6. Confirm normal active worker cycles do not gain full pushes or visible flashes.

No live Samsung inspection is needed while the device is healthy. After controlled tests pass, a
future naturally observed occurrence can be correlated by approximate time with the sparse
handoff/push breadcrumbs.

## Acceptance criteria

1. Sparse candidate data never replaces a complete existing body with an almost blank graph.
2. The prior active location remains displayed while a candidate is loading.
3. A candidate promotes once it has enough provider data to render a useful current/future view.
4. A genuinely new location is not blocked forever merely because historical forecasts were never
   collected there.
5. Returning to a previously cached location restores its fuller graph without a blank intermediate
   body.
6. Multiple movement fixes are coalesced and do not cause one fetch/render per fix.
7. No active GPS polling or faster periodic schedule is introduced.
8. A new widget with no previous body shows an intentional loading state, not raw XML defaults.
9. After a qualifying idle gap, exactly one complete-body push becomes full and rebinds the fake
   launcher.
10. Routine worker body paints and all header-only updates remain partial.
11. The old `KNOWN DEFECT` test is replaced by a passing recovery requirement.
12. Focused JVM, category, build, and emulator integration checks pass.
13. Field success remains a separate evidence bar; it is not inferred solely from model-launcher
    tests.
