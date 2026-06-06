# Desktop Daily-View Header Parity with Android

**Goal:** Make the desktop daily-forecast view's header match the clean, single-band
Android widget header. Remove the duplicated header layers and reclaim the dead vertical
space so the temperature bars fill the panel like they do on Android.

---

## Problem (observed)

Side-by-side screenshots (Android emulator vs desktop distributable, same location/data):

- **Android** draws **one** compact header band: current temp + accuracy delta (top-left),
  date `Fri 5` (top-center), `NWS` source + gear (top-right). Bars fill the rest.
- **Desktop** stacks **three** header layers and wastes ~23% of the panel height:
  1. `StatusBar` line — "Updated 2h ago" (`Main.kt:697`)
  2. Compose `WidgetHeader` — two rows: `[icon 64.5°] … [NWS / Fri 5]` then
     `[Phone GPS (…) 🌡 ⚙ ☁] … [H D]` (`Main.kt:736`)
  3. **A duplicate header drawn *inside the canvas*** by `drawHeader()`
     (`DailyForecastGraph.kt:158`): sun icon + `64.5°` again, date `2026-06-05`
     (different format!), `NWS` again, gear again.

### Root cause

The desktop is a port of Android's `DailyForecastGraphRenderer`, where the header is drawn
**inside the bitmap** (`DailyForecastHeaderRenderer`) because RemoteViews can't easily lay
out chrome over a bitmap. The port kept that in-canvas `drawHeader()` **and** added a Compose
`WidgetHeader` on top of it — so every header element renders twice. The canvas also reserves
`TOP_PADDING_FRACTION = 0.23f` (`DailyForecastGraph.kt:31`) for that now-redundant header,
creating the large empty gap above the bars.

---

## Target: identical to the Android header (one band, nothing extra)

The desktop daily header must match the Android widget header element-for-element. Android's
`DailyForecastHeaderRenderer` draws exactly one band:

```
┌─────────────────────────────┐
│ ☀64.5°+3.9    Fri 5   NWS ⚙ │   <- the ONLY header row
│                             │
│   85°                       │
│    ┃   76°  70°  76°  ...   │   <- bars rise into reclaimed space
│  Today Sat  Sun  Mon  ...   │
└─────────────────────────────┘
```

**Header contains (left→right), matching Android exactly:**
- weather icon · current temp · accuracy delta (`+3.9`) · precip% — left cluster
- date (`Fri 5`) — centered
- API source (`NWS`) · settings gear — right cluster

**Explicitly REMOVED (all desktop-only inventions, none exist on Android):**
- ❌ `H D` view-mode chips and the zoom chip
- ❌ "Phone GPS (lat, lon)" location label (the `config.label` line)
- ❌ "Updated 2h ago" `StatusBar` line
- ❌ the 🌡 thermometer (observations) and ☁ cloud-toggle icons

**Interactions preserved by mirroring Android's tap model (not chips):**
- **Tap the current temp → toggle daily ↔ hourly.** This is exactly how Android does it:
  `HeaderTapTargetHelper.kt:39` binds `ACTION_TOGGLE_VIEW` to `R.id.current_temp`. The temp
  number *is* the view switch, so the `H D` chips are redundant.
- **Tap the source (`NWS`) → cycle source** (already supported, `Main.kt:817`; matches Android's
  `api_touch_zone`).
- **Tap the gear → open Settings** (unchanged).
- **Tap a day column → open hourly for that day** (already supported, `Main.kt:624`).

**Relocated desktop-only features** (they have no Android header equivalent, so move them out of
the band rather than delete the capability):
- **Location picker** → add a "Location" row inside the Settings window (gear). Today it's only
  reachable via the removed "Phone GPS" label, so Settings must gain it (see step 3a).
- **Observations window** → add an entry in Settings (desktop-only feature; no Android analog).
- **Staleness / last-updated** → **nothing on the daily view.** No indicator, amber or otherwise.
  Android shows "last updated" only in the zoomed temperature graph, anchored to the observation
  point as a fetch-dot age label (`TemperatureGraphRenderer.kt:1106`, `staleness.ageLabel`). That
  belongs to the **future hourly-parity work** (see Sequencing), not here.

---

## Implementation Steps

### 1. Delete the duplicate canvas header
**File:** `desktop/.../DailyForecastGraph.kt`

- Remove the `drawHeader(...)` call (line ~84) and the entire `private fun DrawScope.drawHeader`
  (lines 158–195).
- Remove now-unused params/locals: `headerPainter` (line 44), `settingsPainter` (line 45),
  and the `COLOR_HEADER` constant if unreferenced elsewhere (line 29).
- The `DesktopDailyHeader` fields consumed only by `drawHeader`
  (`currentTempText`, `precipText`, `dateText`, `apiSourceText`, `showSettings`) become dead —
  see step 4.

### 2. Reclaim the vertical space
**File:** `desktop/.../DailyForecastGraph.kt:31`

- Lower `TOP_PADDING_FRACTION` from `0.23f` to ~`0.10f` (enough headroom for the high-temp
  labels drawn at `highY - 24f` and the rain `%` labels at `yAt(high) - 40f`, lines 126/151).
- Sanity-check `GRAPH_BOTTOM_FRACTION = 0.76f` (line 32): with the header gone, the bottom band
  (icons + low label + day label at `size.height - 18f`) still needs ~24% — leave as-is unless
  labels clip after the top change. Verify against a screenshot.

### 3. Reduce the Compose `WidgetHeader` to Android's single band
**File:** `desktop/.../Main.kt:736` (`WidgetHeader`), `:697` (`StatusBar`), `:525` (call site)

Collapse the current two-row + status-line header into **one** row matching Android:

- **Keep the primary row only** (line 764): left cluster `[icon] [temp][+delta][precip%]`,
  right cluster `[source] [gear] [date]`. Lay source/gear/date out to mirror Android
  (`DailyForecastHeaderRenderer`): date centered, source + gear top-right. The current right-side
  stacked `Column` (lines 808–835) becomes a single horizontal cluster; move the gear icon up
  here from the deleted secondary row.
- **Wire the current temp as the view toggle** (Android parity, the replacement for `H D`):
  change the left temp `Row`'s `clickable` (currently `onOpenObservations()`, line 771) to flip
  `viewMode` between `DAILY` and the hourly family. Mirror Android's `toggleViewMode`: DAILY →
  `HOURLY`, any hourly mode → `DAILY`. Keep delta/precip% non-clickable display (precip% may keep
  its existing tap-to-`PRECIPITATION`, line 801, since Android has no equivalent and it's handy).
- **Delete the entire secondary row** (lines 841–901): removes location label, 🌡 observations
  icon, ☁ cloud toggle, the zoom chip, and the `H`/`D` `ViewModeChip`s. `ViewModeChip` (and its
  helper) may become unused → remove if so.
- **Delete the standalone `StatusBar`** (remove the call + `Spacer` at `Main.kt:525–526`, and the
  `StatusBar` composable at `:697`) with **no replacement on the daily view.** Last-updated /
  staleness is deferred to the hourly graph (fetch-dot age label) per Sequencing — the daily band
  shows no update status at all, matching Android. (`formatRelativeTime`, `:724`, may become unused
  if no other caller — remove if so.)
- Net: header is one band; the `padding(12.dp)` Column (line 524) now holds just `WidgetHeader`
  + the graph, so the bars start right under the single header row.

### 3a. Relocate location + observations access into Settings
**File:** `desktop/.../SettingsWindow.kt` (currently: API Sources, API Keys, Icon Gallery, Exit)

Removing the header's location label and 🌡 icon strands two features — re-home them:

- **Location:** add a "Location" section that shows `config.label` and a button opening the
  existing `LocationPicker` (`onUpdateLocation`, wired through `Main.kt`). This is the only
  remaining path to change location once the header label is gone, so it is **required**, not
  optional.
- **Observations:** add a small "Stations / Observations" button that fires the existing
  `onOpenObservations()` path (already a Window in `Main.kt:272`). Desktop-only feature, no
  Android analog — Settings is the natural home.
- Thread any new callbacks (`onUpdateLocation`, `onOpenObservations`) into `SettingsWindow(...)`
  at its call site (`Main.kt:316`). Cloud-cover / precipitation views remain reachable by tapping
  a day column (which already resolves a view from the day's icon, `Main.kt:629`); no separate
  toggle needed.

### 4. Trim the now-unused model header fields
**File:** `desktop/.../DesktopDailyForecastModel.kt:27` (`DesktopDailyHeader`) and
`buildHeader` (line ~135)

- If `DesktopDailyHeader`/`buildHeader` are only consumed by the deleted `drawHeader`, remove
  them and the `header = buildHeader(...)` assignment (line 120) plus the `header` field on the
  view-state (line 58). **Grep first** — confirm no other caller (tests reference
  `state.header`?).
- `formatHeaderTemperature` (line 145) may become unused; remove if so.

### 5. Tests
**File:** `desktop/.../DesktopDailyForecastModelTest.kt`, `DesktopUiTest.kt`

- Remove/repoint any assertions on `state.header.*` fields deleted in step 4.
- Remove/repoint any `DesktopUiTest` assertions on the removed header nodes (`H`/`D` chips, the
  location label, the "Updated …" status, the 🌡/☁ icons).
- Add an assertion that **tapping the current temp toggles `viewMode`** (DAILY ↔ HOURLY) — the
  Android-parity replacement for the chips.
- Add a guard that the current-temp string appears **once** in the semantics tree (no second
  Compose temp).
- Add coverage that Settings now exposes the **Location** (and **Observations**) entry.
- Keep existing `daily_forecast_surface` test tag coverage.

### 6. Build, run, screenshot-verify
- `scripts/build-exe-and-restart.sh` (builds, stops running app, relaunches autostart), or for
  fast iteration `./gradlew :desktop:run`.
- Per `feedback_auto_restart_desktop`: after a compiling change, just run
  `restart-desktop-distributable.sh` — no need to ask.
- Capture the desktop window (`import -window <id>`) and the Android emulator
  (`adb -s emulator-5554 exec-out screencap`, convert PNG→JPG per CLAUDE.md) and compare the
  header bands side by side.

---

## Risks / Notes

- **Multiple adb devices attached** (emulator-5554 + two physical phones) — always pass
  `-s emulator-5554`; never run `connectedDebugAndroidTest` (removes widgets from phones).
- **High-label clipping**: lowering top padding risks the tallest day's high label or rain `%`
  label clipping at the top edge. Verify with the hottest day in view; bump padding to ~0.12f
  if needed.
- **Don't strand the relocated features.** The header label is the *only* current path to the
  location picker; step 3a (Settings → Location) must land in the same change, or location becomes
  unchangeable. Same caution for observations.
- **`WidgetHeader` is shared with the hourly/cloud/precip views** (`showWeatherSummary`, zoom
  chip, ☁ toggle are hourly-only affordances on the secondary row being deleted). Deleting the
  secondary row affects those views too — see the decision below; verify hourly view still has its
  zoom + cloud controls (relocate them there if the shared header can't carry them).
- This is **visual** parity, not code-shared parity: Android draws the header in a bitmap;
  desktop keeps it in Compose for native click handling. Pixel-exact matching is a non-goal.

## Sequencing: daily now, hourly later

This plan covers the **daily-view header only**. Full hourly-view parity with Android is a wanted
but separate, larger follow-up — daily does **not** depend on it, and decision (a) below scopes
hourly cleanly out of this change. Do daily first.

**Deferred to a future "hourly-view parity" plan:**
- Move "last updated" / staleness into the hourly temperature graph as an in-graph **fetch-dot
  age label** anchored at `currentObservedAt`, mirroring Android's `TemperatureGraphRenderer`
  fetch dot (`:1106` `FetchDotLayout.staleness`, rendered at `:1164` as `"… (12m ago)"`). The
  desktop `TemperatureGraph` already receives `currentObservedAt` (`Main.kt:574`), so the anchor
  exists.
- Reconcile the hourly header's zoom chip / ☁ toggle controls with Android's model.
- Match hourly graph labels, NOW indicator, and layout to Android.

## Out of scope
- Hourly-view *visual* redesign (only the daily header must match Android here; hourly keeps its
  current controls.
- Android-side changes — Android already looks correct.  Can refactor for code sharing.
