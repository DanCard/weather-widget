# Remove the hardcoded Mountain View default; show an error when no location is available

**Date:** 2026-08-12 · **Plan:** [plans/260812-remove-default-location-and-show-error-when-unavailable.md](../plans/260812-remove-default-location-and-show-error-when-unavailable.md)
**Target:** H1 from `plans/260812-code-review-refresh-coordination.md`

`WeatherWidgetWorker.DEFAULT_LAT/LON` (Google HQ, 37.4220/-122.0841) is gone. `ActiveLocationResolver.resolve()`
used to end in `?: (DEFAULT_LAT to DEFAULT_LON)`, so a user with nothing resolvable had live weather
fetched for Google HQ and `getLocationName()` labelled it **"Mountain View, CA"** — someone else's
weather presented as their own. "No location" is now the *absence* of coordinates: the worker paints
"No location — tap to set" and fetches nothing.

---

## What shipped

| # | Commit | Change |
|---|---|---|
| 1 | `d79e7f8c` | `widget_no_location` (19 locales), `Origin.NO_LOCATION`, `WidgetRenderer.updateWidgetNoLocation` |
| 2 | `6477b65a` | **Legacy-sentinel migration** — erases Google-HQ coords from upgrading installs |
| 3 | `293fe744` | Nullable `resolve()`; worker gated at all 5 main-source call sites |
| 4 | `7851029d` | Placeholder becomes unset in `ConfigActivity` / `LocationUpdater` |
| 5 | `a15ad812` | `HourlyObservationBackfill` / `WorkInput`: unset means unanchored |
| 6 | `d66c57fa` | Handlers degrade gracefully instead of defaulting to a coordinate |
| 7 | `7ba5a7b8` | Constants deleted from `WeatherWidgetWorker` |
| 8 | `e100d3d1` | No-location paint on `onUpdate` + instrumented test |
| 9 | `aa7e23ff` | `CLAUDE.md` + plan file updated |

**Verified:** 1911 unit tests pass · 90 instrumented pass (2 skipped) on emulator-5554 · app,
androidTest and `:desktop` all compile · `grep -rn "DEFAULT_LAT|DEFAULT_LON"` returns hits in exactly
one file, the migration.

---

## Three things worth attention

### 1. The `sameSite` insistence was not theoretical

The plan required the migration compare with `LocationMatch.sameSite`, never `==`. Flipping it to `==`
to prove the test could fail made **five of seven** migration tests fail, including the basic
"clear the sentinel" case — because the `Float` pref round-trip alone makes
`37.4220f.toDouble() != 37.4220`.

An `==`-based migration would have been a **silent no-op on every install**, and every affected user
would have been permanently pinned to Mountain View with the GPS auto-heal disabled — strictly worse
than the bug being fixed. This is a second, independent mechanism on top of the 3-dp quantization one
already documented in `HourlyObservationBackfill`'s KDoc (−122.0841 → −122.084); either alone defeats
`==`.

### 2. The instrumented test found a real gap, not just a confirmation

`WidgetStartupCoordinator` painted "Loading…" whenever there was no cached weather and relied on the
enqueued sync to replace it. With no location that sync now returns without painting, so the
placeholder would stick until the user happened to open the app — the stranded-placeholder failure
class this codebase has hit repeatedly.

Commit 8 fixes it and deliberately skips **only** the placeholder and the immediate sync:
`schedulePeriodicSync` still runs, because the periodic full sync carries the GPS auto-heal that
rescues the widget. An early return there would have stranded a no-location widget with no way to
recover on its own.

### 3. A latent bug fixed as a side effect

Dropping the equals-default check from `LocationUpdater.allWidgetsAtDefault` means a user who
genuinely lives near Google HQ no longer has their deliberate choice classified as a placeholder
eligible to be overwritten by the heal. Proximity matching now exists **only** in the one-time
migration, never in the steady state.

---

## Design invariants established

- **"No location" = absent/NaN coordinates.** `allWidgetsAtDefault` tests that and nothing else.
- **`shouldHealTo` uses `getStoredWidgetLocation`, not `getWidgetLocation`.** The latter falls back
  through the legacy delta store *and* `historicalPoiFallback()`, so a never-configured widget would
  resolve to an inferred coordinate and read as "already located" — silently disabling the heal for
  exactly the widget that needs it.
- **Any weather data implies a location** (`resolve` falls back to `forecastDao.getLatestWeather()`),
  so no-location ⟹ no data ⟹ nothing to render. Interactions (zoom / nav / source toggle) correctly
  abort and paint the no-location state.
- **`Double.NaN` is the in-pipeline "unknown coordinate."**
- **Never reintroduce a stand-in coordinate, including a DEBUG-only one** — that is how this got in.

---

## Deviations from the plan

**§7 used `Double.NaN`, not `Double?`.** Threading nullable coordinates through the graph pipeline
rippled into ~14 call sites across the renderers, for code that is defence-in-depth by construction
(§2c already guarantees the worker never paints for a null location). NaN removes every fake
coordinate with a far smaller diff and degrades honestly at each consumer: sun shading falls back to
`UNKNOWN_LOCATION`, IDW distance weights drop out, and a NaN climate-normals key simply misses the
cache.

Two consumers throw on NaN and needed explicit guards:
- `SunPositionUtils.getSunInfo` — `require(lat in -90.0..90.0)`; routed through a new
  `getSunInfoOrUnknown`.
- `ClimateNormals.locationKey` — `roundToInt` throws on NaN; guarded in `ClimateGapFiller`.
  **Caught by an existing test (`HistoryIconVisibilityRoboTest`), not by inspection.**

**Two sites got a real skip instead of NaN**, because their coordinates are not decoration:
- `setupHistoryShortcut` puts lat/lon into an Intent extra, so a placeholder would open
  `ForecastHistoryActivity` at Google HQ. With no location the shortcut is simply not bound.
- `WidgetRenderer`'s location is the site every row is unified against, so it paints the no-location
  state — the last line of defence for direct render paths that bypass the worker.

**One extra commit beyond the plan's seven** (`e100d3d1`), from the instrumented test finding above.

---

## Test changes and why

- **New:** `LegacyDefaultLocationMigrationTest` (7 tests, incl. quantized-sentinel and
  runs-exactly-once), `NoLocationWidgetIntegrationTest` (instrumented, end-to-end).
- **Robolectric tests that never set a location** (zoom cycling, cloud-cover view mode, API toggle,
  history icons) passed only via the Google-HQ fallback. They now set one, because their subject is
  widget-state mechanics, not location handling.
- **Label assertions updated:** Settings and setup screens used to render
  `"Default Location: 37.4220, -122.0841"`; they now read `"No location set"`.
- `NoLocationWidgetIntegrationTest` **snapshots and restores** the per-widget coordinate prefs rather
  than clearing them. `IsolatedIntegrationTest` isolates only `WidgetStateManager`'s own file, so
  widgets already on the device supply a stored location and defeat the test — but the suite must
  never damage a real device's widget locations.

---

## Still open (deliberately deferred)

Delete `LegacyDefaultLocationMigration` and its two `LEGACY_DEFAULT_LAT/LON` constants once
`LOCATION_MIGRATION` telemetry (an `app_logs` row emitted by the first worker run after an upgrade
that cleared anything) shows the migration has run everywhere that matters. Until then the
acceptance grep is "hits in exactly one file", not zero. See §8 of the plan.
