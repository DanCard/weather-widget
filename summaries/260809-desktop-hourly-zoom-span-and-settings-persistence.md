# Desktop: Hourly Zoom Span Honoured End-to-End, and Settings That Actually Stick

**Date:** August 9, 2026
**Baseline:** uncommitted work on top of `e29d139b` ("Fix hourly label clutter, date the station
reading, translate Tip Jar")
**Platform:** desktop only — Android was unaffected by every bug below
**Status:** Implemented and verified. `:desktop:test` and `:shared:test` fully green; rebuilt,
restarted and confirmed on screen.

---

## 1. What was asked, and what it turned into

It started as one request — *"an integration test for desktop that checks that the number of hours
specified in settings matches what is displayed in narrow hourly view"* — and the test kept finding
things. Four distinct defects came out of it, in three different layers:

| # | Defect | Layer |
|---|---|---|
| 1 | Settings said 5 h, the *window* was 6 h | zoom-factor inversion |
| 2 | Changing the setting reverted on save | Compose state ownership |
| 3 | Reverted again, after save, from a second writer | close-path draft flush |
| 4 | Settings said 6 h, the *painted axis* was 5 h | point filter |

Plus one small feature: the Settings window now remembers its size and position.

---

## 2. Defect 1 — the window did not match the setting

`zoomFactorForStage` converted a discrete stage into desktop's continuous `zoomFactor` by
**inverting against back hours only**. But the renderer derives *forward* hours from a different
geometric curve (`MAX_FORWARD_HOURS` 168 vs `MAX_BACK_HOURS` 720), so the two agreed only by luck.

| Setting | Was | Now |
|---|---|---|
| 4 h | 4 h ✓ | 4 h |
| **5 h (default)** | **6 h ✗** | 5 h |
| 6 h | 6 h ✓ | 6 h |
| 7 h | 7 h ✓ | 7 h |
| **8 h** | **7 h ✗** | 8 h |

**Fix:** NARROW now picks the factor whose *rendered* `back + forward` equals the configured span,
precomputed once per span (five values) using the same scan technique `dayViewZoomFactor` already
uses. WIDE and THREE_DAY keep the back-hours inversion — they are points on a curve, not promises to
the user.

**Why existing coverage missed it:** `DesktopGraphZoomTest` round-trips the factor against *back*
hours and checks the stage snaps back to NARROW. Both passed throughout. Nobody asserted
`back + forward` against the setting — the only number the user ever sees.

---

## 3. Defects 2 and 3 — the setting would not stick

### 3a. The draft was discarded by every unrelated config write

`SettingsWindow.kt` held its draft as `remember(config) { mutableStateOf(config) }` — **keyed on the
persisted baseline**. Whenever `config` changed identity, Compose discarded the draft and re-seeded
from the new value.

`DesktopConfig` has **six independent writers**, and the popup saves constantly: window move/resize
(1 s debounce), zoom scroll, pan, view switches, day clicks. The log showed **10–11 config saves per
minute** during normal use. The draft only lives in memory for 5 s before auto-save, so an edit made
in that gap was routinely wiped: the control snapped back, the `Save •` dirty marker cleared itself,
and the subsequent Save click was a silent no-op — which is exactly why it looked like Save failed.

**Fix, in two halves.** Un-keying the `remember` alone would have created the mirror bug: the draft
carries a stale snapshot of every popup-owned field, so saving it verbatim rewinds the window
position and zoom the user just changed. So ownership is now explicit:

- `DesktopConfig.withSettingsFrom(draft)` — the 9 Settings-owned fields (`weatherSource`,
  `visibleSources`, `apiKeys`, `narrowZoomSpanHours`, `personalStationDiscount`, `useCelsius`, and
  the three `todayOverlay*` flags). Everything else — window bounds, `zoomFactor`, `hourlyOffset`,
  `dateOffset`, `viewMode`, plus `lat`/`lon`/`label`, which the location picker writes directly —
  belongs to the popup.
- The draft **rebases** on a new baseline: popup fields from the newer baseline, settings fields from
  the draft.
- `isDirty` compares only the settings half, so popup churn no longer latches a permanent `Save •`.

### 3b. …and then a second writer clobbered it after save

With 3a in place the log proved the save itself worked — `SETTINGS_SAVE narrowZoomSpanHours: 5 -> 6`
— and then three later writes put it back. `flushSettingsDraft` (title-bar X / Escape) saved the
draft **verbatim**, stale popup fields and all. It now merges through `withSettingsFrom` like the
Save button and auto-save already do.

### 3c. Logging (the thing that made 3b findable)

The original question was *"is there something in the log that shows the problem?"* — and there
wasn't: nothing logged setting values at all. Added:

| Tag | When |
|---|---|
| `SETTINGS_EDIT narrowZoomSpanHours: 5 -> 6` | a control moves |
| `SETTINGS_REBASE onto new baseline; kept unsaved …` | a popup save lands mid-edit — the moment that used to eat the change |
| `SETTINGS_AUTOSAVE` / `SETTINGS_SAVE` | what was persisted, by value |
| `SETTINGS_SAVE no-op (nothing dirty)` | the silent failure |
| `CONFIG_SAVE source=<tag> settings-fields-changed: …` | **WARN** when a settings field changes on a non-settings save — a clobber by definition |

`saveConfigAndNotify` now takes a **mandatory** source tag, so the compiler forces every writer to
identify itself: `settings`, `settings-close`, `settings-window-geometry`, `popup`,
`popup-window-geometry`, `observations`, `observations-window`, `location-picker`.

```bash
grep -E "SETTINGS_|CONFIG_SAVE" ~/.local/state/weather-widget/autostart-*.log
```

---

## 4. Defect 4 — the setting stuck, but the graph painted an hour less

With the setting finally persisting at 6 h, the graph still showed 5. Different bug entirely.

`xAtTime` maps `points.first()..points.last()` across the full canvas width — **deliberately**, so
the curve reaches both edges (see `HourlyGraphCanvasGeometry`'s doc). So the visible axis is the
*data* span, and any point the filter drops shortens the graph.

The filter was `it.dateTime >= start && it.dateTime < cutoff` — **asymmetric**. But
`temperatureGraphHourWindow` builds `endMs` as `alignedCenter.plusHours(forwardHours)`, an hour mark
that *belongs to* the view rather than one past it, and hourly data lands exactly on those marks.
Start inclusive + end exclusive ⇒ the last point vanished ⇒ every view painted one hour less than it
queried. A 6 h window drew 5 h.

**Fix:** extracted `hourlyPointsInWindow(...)` out of the composable — both a testability seam and
the fix — using `it.dateTime in startMs..endMs`. The data-span mapping is untouched.

**Not narrow-view-only.** WIDE queries 20 h (12 back + 8 forward) and was painting **19 h**. Every
desktop zoom has been an hour short; the configurable setting merely made it nameable. Consequence
worth knowing: **every desktop hourly view is now one hour wider than before.**

### My test missed this, and the user caught it

The first version of `NarrowZoomSpanDisplayedHoursTest` asserted `cutoff - start` — the *queried
window*, which was correct all along — and never modelled the point filter or the x mapping. It
stopped one link short of the screen: precisely the criticism this same file's header makes of the
pre-existing zoom tests. Corrected by asserting the painted span
(`points.last().dateTime - points.first().dateTime`).

---

## 5. Feature — the Settings window remembers its size

`settingsWindowX/Y/Width/Height` on `DesktopConfig`, mirroring the popup/observations/history
windows, persisted on the same 1 s debounce and tagged `settings-window-geometry`. Deliberately
**popup-owned, not settings-owned**: dragging the Settings window mid-edit must not make the window
rewind itself.

---

## 6. Verification

**New tests — 14 across three files, each verified to fail without its fix** (by neutering the fix
and re-running):

`NarrowZoomSpanDisplayedHoursTest` (8)
- The graph **paints** exactly the configured hours, spans 4–8.
- Window == painted span across the **whole zoom curve** (7 factors), not just the NARROW band.
- Both window endpoints included; a 6 h window on hourly data is 7 points.
- The default is honoured out of the box (this one was live-broken).
- Clicking through the real `handleToggleZoom` cycle lands on the configured span.
- Back/forward match the shared ceil/floor split — a 1-back/4-forward window totals 5 h and is still wrong.
- A hand-edited out-of-range `config.json` value clamps rather than rendering a 0 h or 1000 h view.

`SettingsDraftRebaseTest` (6) — the ownership split as pure functions, including a guard that every
field in `SETTINGS_OWNED_FIELDS` survives a rebase, so a setting added to the UI but forgotten in the
merge list fails loudly instead of silently reverting the way `narrowZoomSpanHours` did.

`SettingsWindowBaselineChangeTest` (3) — the bug through the **real window** via the existing Compose
harness: edit a setting, push a new baseline mid-edit, assert `Save •` is still lit and the click
persists the edit without rewinding the popup's newer window position. Plus a burst of 8 rapid
baselines, matching the 1 s-debounce write storm you get while dragging a window.

**Failure messages when the fixes are removed** (the proof they are not vacuous):

```
settings say 4h; the drawn axis must span 4h expected:<4> but was:<3>
at zoomFactor=0.0 the window is 4h but the graph paints 3h expected:<4> but was:<3>
last point must be the window end expected:<1754010000000> but was:<1754006400000>
settings say 5h but the narrow view renders 3h back + 3h forward = 6h expected:<5> but was:<6>
a fresh install must render its default 5h expected:<5> but was:<6>
SettingsWindowBaselineChangeTest > pendingEdit_survivesAPopupConfigSave… FAILED
```

**Suites:** `:desktop:test` green · `:shared:test` green.
**Live:** rebuilt via `scripts/buildStart-desktop.sh`, restarted, screenshotted. With the setting at
6 h the footer reads `5p … 11p` — six hours. `config.json` holds `narrowZoomSpanHours: 6` and
`zoomFactor: 0.051`, the factor that renders 3 back + 3 forward.

---

## 7. Follow-ups

- **Precip and cloud graphs were not touched.** They use a separate window in `HourlyGraphInput` with
  its own quirk (it pads the start by an hour), so they may still disagree with the temperature graph
  by an hour. Worth unifying so the three stacked views span identically — deliberately left out
  rather than change three surfaces on one report.
- `DesktopConfig` having six independent writers is the underlying design smell. `withSettingsFrom`
  fences the Settings window off; the popup/observations/picker writers still overlap freely.
- The Android widget reads `zoom.backHours`/`forwardHours` straight off `ZoomStage.window(span)`,
  which is exact by construction, and its Settings persist through `SharedPreferences` — none of
  these four defects apply there. No Android change was made or needed.
