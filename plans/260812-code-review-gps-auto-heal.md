# Code Review — GPS Auto-Heal / Self-Heal Subsystem

**Date:** 2026-08-12 · **Scope:** the location auto-heal path, as reshaped by today's
"remove the default location" change set (`d79e7f8c` … `e100d3d1`).
**Companion docs:** [plan](260812-remove-default-location-and-show-error-when-unavailable.md) ·
[summary](../summaries/260812-remove-default-location-and-show-error-when-unavailable.md)

**Files reviewed:**
- `widget/GpsResampler.kt` — background/foreground sampler, the heal entry point
- `widget/LocationHandoffStore.kt` + `LocationHandoffPolicy.kt` — candidate persistence and promotion policy
- `ui/LocationUpdater.kt` — active-location writes, `allWidgetsAtDefault`, `shouldHealTo`
- `widget/ActiveLocationResolver.kt` — canonical location resolution (now nullable)
- `widget/LegacyDefaultLocationMigration.kt` — one-time sentinel erase
- `widget/WidgetLocationStore.kt` / `WidgetStateManager.kt` — per-widget coordinate storage
- `widget/WeatherWidgetWorker.kt` (full-sync + current-temp gates), `widget/WidgetStartupCoordinator.kt`
- `widget/WidgetRenderer.kt` (`updateWidgetNoLocation`), `widget/handlers/WidgetIntentActionHandler.kt`
- `widget/ScreenOnReceiver.kt`, `ui/MainActivity.kt`, `ui/ConfigActivity.kt`, `ui/LocationFixFlow.kt`

---

## 1. Does the design make sense?

**Yes.** The shape is right and the reasoning in the commit messages is unusually careful.

The heal is a two-phase handoff, which is the correct structure for this problem:

```
passive lastLocation fix  →  GpsResampler.healIfNeeded
      ↓ not sameSite with what we show
  LocationHandoffStore.propose()   → candidate (display unchanged)
      ↓ worker fetches AT the candidate
  evaluateCandidateUsability()     → useful?
      ↓ yes
  promoteCandidateIfMatches()      → candidate becomes the active location
```

Four things are genuinely well done:

- **Candidate-before-commit.** The display keeps its last useful body while new-site weather is
  being collected, so driving through three forecast sites doesn't produce three repaints and three
  fetch storms. `MOVING_GRACE_MS` + "complete visible coverage" is a sensible two-way test.
- **Passive-only sampling.** Every background path reads `lastLocation`; the one active fix is the
  user's own button tap in `ConfigActivity`. That constraint is stated in the KDoc at
  `GpsResampler.kt:24-29` and actually held everywhere I checked.
- **`sameSite` everywhere, never `==`.** The migration's KDoc (`LegacyDefaultLocationMigration.kt:24-27`)
  proves the point by having flipped it and watched 5/7 tests fail. Correct lesson, correctly applied.
- **Optimistic-concurrency promotion.** `promoteIfMatches` re-reads the candidate and compares
  `firstSeenMs` before committing, so a resample that lands mid-fetch can't promote the wrong site.

The core objection the change set answers — "a coordinate nobody chose must never be fetched or
labelled" — is right, and the no-location dead end is the honest way to express it.

**But**: the change set does not actually reach the population it targets (H1), and the live heal
path violates one of the invariants the summary claims for it (H2).

---

## 2. Issues (severity-ranked)

### 🔴 High

#### H1. The migration erases the sentinel from prefs, and `resolve()` puts it straight back from the database.

`LegacyDefaultLocationMigration` clears two things: `active_weather_location`, and the
`widget_lat_*`/`widget_lon_*` prefs (`LegacyDefaultLocationMigration.kt:94-125`). It never touches
the `forecasts` table — and the coordinates live there too, on every row fetched for Google HQ over
the past month.

`ActiveLocationResolver.resolve()` ends with (`ActiveLocationResolver.kt:64-74`):

```kotlin
val resolved = configuredLocation
    ?: forecastDao.getLatestWeather()?.let { it.locationLat to it.locationLon }
    ?: return null
persist(context, resolved.first, resolved.second)     // ← writes it back as canonical
syncCompatibilityCopies(stateManager, appWidgetIds, resolved)   // ← and back into widget prefs
```

`getLatestWeather()` is `SELECT * FROM forecasts ORDER BY batchFetchedAt DESC LIMIT 1` — location-blind
(`ForecastDao.kt:24`). So on the first worker run after upgrade:

1. `current()` → null (migration cleared it)
2. `getStoredWidgetLocation()` → null (migration cleared it)
3. `getLatestWeather()` → **a Google-HQ row**
4. `persist(37.422, -122.0841)` → the sentinel is canonical again, *and* re-written into the
   per-widget prefs by `syncCompatibilityCopies`
5. `KEY_MIGRATED` is already `true`, so the migration never runs again

The user is back on Mountain View weather, labelled via `FriendlyLocationName` as before. The
no-location state never paints, and the heal is no better off than it was yesterday — for the
*exact* population the migration exists for. Note who that population is: they are at the default
**because their GPS never resolved**, so the heal has no fix to offer them either.

This isn't hypothetical. It's asserted as intended behaviour by
`ActiveLocationResolverTest.kt:66` ("resolve uses latest weather coordinates when no widgets exist
but weather database has data"), and the instrumented test only avoids it by seeding no forecasts at
all — the test's own KDoc says "any seeded row would supply a location and defeat the test."

The summary lists this as a design invariant — "Any weather data implies a location, so
no-location ⟹ no data ⟹ nothing to render." That holds for a fresh install. It is exactly false for
an upgrade, where a month of data implies only that *the old default* had a location.

**Fix (smallest correct one):** extend the deferred-report hook in the worker, which already runs at
`WeatherWidgetWorker.kt:75-77` — before every `resolve()` call in the file. When the report says
coordinates were cleared, also delete `forecasts`/`hourly_forecasts` rows that are `sameSite` with
the legacy pair, so step 3 finds nothing. The migration itself still touches no database, which was
the reason for the deferral. A user genuinely near HQ loses cached rows and re-fetches once; the
migration already decided to un-pin them, so this is consistent, not a new cost.

Worth a breadcrumb (`LOCATION_MIGRATION rows_purged=N`) so rollout telemetry can tell "nothing to
clear" apart from "cleared and then resurrected."

---

### 🟠 Medium

#### H2. The live heal reads *inferred* locations — the thing the summary says it must not do.

The summary states the invariant plainly:

> `shouldHealTo` uses `getStoredWidgetLocation`, not `getWidgetLocation`. The latter falls back
> through the legacy delta store *and* `historicalPoiFallback()`, so a never-configured widget would
> resolve to an inferred coordinate and read as "already located" — silently disabling the heal for
> exactly the widget that needs it.

That is right, and `shouldHealTo` obeys it (`LocationUpdater.kt:44-49`). The problem is that
`shouldHealTo` is **not on the heal path** (see M1) — and the two functions that *are* both use the
inferring resolver:

- `GpsResampler.healIfNeeded` → `ids.toList().firstNotNullOfOrNull(stateManager::getWidgetLocation)`
  (`GpsResampler.kt:100-101`)
- `LocationUpdater.proposeFollowDeviceLocation` → same call (`LocationUpdater.kt:229-230`)

`WidgetLocationStore.resolve()` is `stored() ?: legacyLocation() ?: historicalPoiFallback()`
(`WidgetLocationStore.kt:23-26`), so "no location" quietly becomes "the last place you ever saved."

The comment added today directly above that line reads *"Null when the app has no location at all —
the state this heal exists to escape."* It is not null in that state whenever a POI exists.

Concrete dead end, reachable through a path this change set created: the user hits
Settings → Set Location → "Use precise device location", the fix times out, `LocationFixFlow` returns
`Outcome.Default`, and `saveNoLocation` runs (`ConfigActivity.kt:543-558`). That clears the active
location and the widget prefs but **not** `historical_pois`. The widget now says "No location — tap to
set." The next resample gets a cached fix near home, `active` resolves to the old POI (home),
`sameSite` → `outcome=same_site`, no candidate, no heal. The widget stays on the error message
permanently even though the app has a perfectly good fix in hand. Settings meanwhile still prints
the old location, because `effectiveLocation` falls back to POIs too (`LocationUpdater.kt:91-104`).

The same inference reaches the observation backfill: `resolveBackfillLocation` is fed
`getWidgetLocation`, so with no location it anchors observations at a stale POI — the "coordinate
nobody chose" that its own KDoc says must skip.

**Fix:** switch both call sites to `getStoredWidgetLocation`, and drop `historicalPoiFallback()` from
`WidgetLocationStore.resolve()` entirely.

**Correction to my first recommendation:** I initially suggested `clearActiveLocationForAllWidgets`
should also clear `historical_pois`. It should not. That list is the app's *label* store —
`FriendlyLocationName.cached()` reads it to name a coordinate (`FriendlyLocationName.kt:36`) and
`CurrentTempRepository` maintains it — so clearing it would lose place names the app can't cheaply
recover. The list isn't the problem; treating it as a *coordinate* source is. Fix the readers, keep
the data.

#### H3. `allWidgetsAtDefault` and `shouldHealTo` are dead code that four documents describe as load-bearing.

Neither has a production caller. Full census outside tests:

| Reference | Kind |
|---|---|
| `CLAUDE.md` — "`LocationUpdater.allWidgetsAtDefault` (the GPS auto-heal signal)" | doc |
| `ConfigActivity.kt:537` — "`allWidgetsAtDefault` reports true precisely because…" | comment |
| `WidgetRenderer.kt:100` — "The GPS auto-heal keeps running behind this state (see `allWidgetsAtDefault`)" | comment |
| `LegacyDefaultLocationMigration.kt:12-16` — "`allWidgetsAtDefault` would stop trying" | comment |
| `LocationUpdaterTest`, `NoLocationWidgetIntegrationTest:148` | tests |

The heal's real gate is `GpsResampler.healIfNeeded`'s `sameSite` comparison, which never consults
either function. Both functions are correct in isolation, and today's change to `allWidgetsAtDefault`
(dropping the proximity test) is the right change — it just doesn't affect any behaviour.

This matters because the migration's entire rationale is phrased in terms of a function that gates
nothing: "allWidgetsAtDefault would return false, so the GPS auto-heal would stop trying." The
migration *is* still needed, but for the reason in its second bullet (`current()` returning the
sentinel), not the first. A future reader reasoning from these comments will reason wrongly.

**Fix:** delete both functions and their tests, and repoint the four doc references at
`GpsResampler`/`LocationHandoffStore`. If `allWidgetsAtDefault` is meant to stay as a diagnostic,
say so where it's defined and stop describing it as the signal.

#### H4. "No location — tap to set" is not tappable.

`updateWidgetNoLocation` builds fresh `RemoteViews` and pushes them with `partialPush = false`
(`WidgetRenderer.kt:104-131`). A full push replaces the whole view tree, so every `PendingIntent`
from the previous render is gone — including the root dead-zone catch-all that
`setupDeadZoneCatchAll` installs on every normal render, and whose KDoc explains the consequence:

> On Samsung (One UI Home), a tap that lands on a region with no PendingIntent falls through to
> launching the app's LAUNCHER activity (MainActivity), which we don't want.
> — `TemperatureTouchTargets.kt:554-560`

So the string instructs an action that does nothing on stock launchers and opens the wrong screen on
Samsung. The renderer's own KDoc claims "Tapping opens `ConfigActivity` to set one" — nothing in the
method sets that intent, and `ConfigActivity` is currently launched from exactly one place in the
app, `SettingsActivity.kt:458`.

This compounds H2: when the heal is blocked, the tap is the only remaining escape, and it is inert.

**Fix:** set a `ConfigActivity` `PendingIntent` (with `EXTRA_APPWIDGET_ID`) on `R.id.widget_root` in
`updateWidgetNoLocation`. `updateWidgetError` has the same gap behind "Tap to refresh" — pre-existing,
but the same one-line shape fixes it.

#### H5. Acquisition and following are the same code path, so a first-ever location waits out a grace built for a moving car.

There are two operations here, and only one of them is what the "heal" name describes:

| | **Acquisition** | **Following** |
|---|---|---|
| From → to | no location → any location | site A → site B |
| On screen meanwhile | "No location — tap to set" | a perfectly good body for A |
| Right bias | commit as soon as anything is fetchable | be conservative; don't flap between towns |

`evaluateCandidateUsability` implements only the second: 3 days of daily coverage, then either 22
complete hours across the ±12h visible window, or forward coverage **after `MOVING_GRACE_MS` (30 min)**
(`LocationHandoffPolicy.kt:38-100`). It never asks whether an active location exists, so acquisition
inherits caution that exists to protect a body it doesn't have.

Two costs stack:

- Before ~10am the visible window reaches back before the hourly data starts, so `completeVisible`
  fails and the 30-minute grace applies.
- Promotion is only attempted inside a **full sync** (`WeatherWidgetWorker.kt:393-410`), and the
  candidate refresh enqueued at proposal time has already been spent. The next attempt is the
  periodic sync: 60 min plugged, 240 min at 20–50% battery, 480 min below 20%.

So a fresh install can fetch its weather successfully at 08:05 and still show "No location — tap to
set" until well into the afternoon. Every one of those syncs fetches at the candidate coordinates
and returns without painting.

**Fix:** make the distinction explicit rather than tuning the constant. `evaluateCandidateUsability`
takes an `isAcquisition` flag (`ActiveLocationResolver.current() == null`); when set, daily coverage
alone promotes and the grace and hourly requirements don't apply. Tuning `MOVING_GRACE_MS` instead
would trade the two cases off against each other forever — they want opposite biases, so they need
separate answers, not a compromise value.

The vocabulary is what hid this: "heal" implies a broken state being repaired, which is true of
neither case. Following a moving device isn't repair, and an unset location isn't damage. See
§5 for the naming follow-up.

---

### 🟡 Low

**L1. `outcome=same_site` is logged when the real outcome is "no widgets."**
`GpsResampler.kt:92-96` emits `outcome=same_site trigger=$trigger reason=no_widgets`. These
breadcrumbs are the debugging interface for this subsystem (per the class KDoc), and
`grep 'outcome=same_site'` is how you'd answer "why didn't it heal?" Give it its own token —
`outcome=skipped_no_widgets`.

**L2. Two implementations of the `historical_pois` format, one of which parses it wrong-but-works.**
`WidgetLocationStore.historicalPoiFallback()` splits the whole string on `|` without splitting on `;`
first (`WidgetLocationStore.kt:54-63`); `LocationUpdater.effectiveLocation` splits on `;` then `|`
(`LocationUpdater.kt:93-104`). The former happens to land on the last entry's coordinates because
`takeLast(3)` skips past the `;`, but it's accidental, and it breaks the moment a geocoded label
contains a `|`. One parser, shared. (If H2 is taken, the fallback disappears from the store entirely
and this resolves itself.)

**L3. `LocationFixFlow`'s KDoc still describes the deleted behaviour.**
"try an active fix, fall back to the cached last fix, **then to the hard default**" and
`Outcome.Default` (`LocationFixFlow.kt:6-9, 33-36`) — the outcome now means "give up and record no
location." The name reads as "use the default coordinate," which is precisely the concept that was
removed. Rename to `Outcome.NoFix` and fix the KDoc.

**L4. `clearActiveLocationForAllWidgets` uses `apply()` where the rest of the subsystem uses `commit()`.**
`WidgetLocationStore.clearWidget` (`:47-52`) is the only `apply()` among these writes, and it's
immediately followed by `enqueueForceRefresh`. Same-process reads see the in-memory map so this is
safe today, but the mismatch is worth removing given the durability reasoning documented in
`writeActiveLocation` (`LocationUpdater.kt:252-263`).

---

## 3. What I checked and found correct

- The nullable-`resolve()` gates: all five main-source call sites gate, and the full-sync gate is on
  `candidate ?: active` rather than `active` alone, so a handoff on a never-configured install still
  fetches (`WeatherWidgetWorker.kt:342-348`). That subtlety is easy to get wrong and isn't.
- `renderNoLocationAndFinish` returning `success()` rather than `retry()` — correct; retrying a
  settled state only burns wakeups.
- Deliberately ignoring the screen-off paint skip there — correct, and the reasoning
  (first-ever run stranded behind "Loading…") matches the failure class this repo has hit before.
- `WidgetStartupCoordinator`'s new branch keeps `schedulePeriodicSync` outside the early return, so a
  no-location widget still gets the sync that carries the heal. This was the right instinct and the
  instrumented test earned its place by finding it.
- The interaction handlers paint before aborting, so a tap is never silently dropped
  (`WidgetIntentActionHandler.prepareContext`).
- `LocationMode.FIXED` skips are checked twice in `GpsResampler` (once to avoid the Play-services
  call, once in the shared tail) — cheap and correct; the foreground path enters at `healIfNeeded`
  and would otherwise miss the first check.
- `a15ad812` (backfill NaN handling) is clean: `Double.NaN` defaults plus `isFinite()` guards at both
  the work-input and the pure-resolution seam, with the `==`-vs-`sameSite` lesson preserved in the
  KDoc after the code it described was deleted.

---

## 4. Suggested order of work

1. **H1** — the change set doesn't reach its target population without it.
2. **H2 + H4** — together they turn "temporarily no location" into "permanently no location, with an
   inert instruction on screen."
3. **H5** — the acquisition/following split; quality-of-life for every fresh install.
4. **H3** — delete the dead pair and repoint the docs before someone reasons from them.
5. **L1–L4** — cleanups.
6. **Rename** (§5) — mechanical, last, and only after H3 has deleted what would otherwise be renamed.

Implementation plan: [260812-fix-gps-heal-findings-acquisition-vs-following.md](260812-fix-gps-heal-findings-acquisition-vs-following.md).

---

## 5. Naming follow-up: "heal" is a fossil

The word was accurate once. When the app wrote Google HQ as the "GPS never resolved" placeholder, the
widget really was in a broken state — showing another town's weather as the user's own — and something
had to come along later and repair it. The detector was named for the injury: `allWidgetsAtDefault`.

Today's change set deleted the injury. What remains is acquisition and following (see H5), neither of
which is damage. The evidence that the vocabulary has outlived its mechanism is already in the tree:

- `LocationMode.kt` — the constant is `FOLLOW_DEVICE`; its KDoc three lines down calls the
  implementation "the GPS auto-heal." The user-facing name is the correct one.
- `allWidgetsAtDefault`, the function named for the injury, is dead code (H3) that four documents
  still introduce as "the GPS auto-heal signal."
- `LocationFixFlow.Outcome.Default` (L3) — same fossil, one layer up.

Scope: 49 `heal` occurrences under `app/src/main`, of which only 4 are identifiers on this path
(`healIfNeeded`, `shouldHealTo`, `maybeAutoHealLocationFromGps`, and the `GpsResampler` KDoc's
"heal paths"). The rest are comments, plus `CLAUDE.md`.

**Out of scope, keeps its name:** `WeatherDatabase.healCorruptDatabaseVersion`, the blank-widget render
self-heal, and `syncCompatibilityCopies` restoring the one-site invariant. Those repair a violated
invariant, one-shot, with a defined correct state to return to. That's what healing is, and the
contrast is the reason to stop using the word for device movement.
