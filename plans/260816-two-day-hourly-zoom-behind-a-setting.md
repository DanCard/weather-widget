# Two-day hourly zoom, behind an off-by-default setting (2026-08-16)

## Goal

Replace the 3-day hourly zoom stage (72 h = 48 back / 24 forward) with a **2-day** stage
(48 h = **42 back / 6 forward**), and put it behind a new app setting that is **off by default**.
When the setting is off, the multi-day stage is not in the tap/click cycle and cannot be reached
on Android.

Background: the 3-day view duplicates the daily view's job at widget width (~3 px/hour), and the
widget's only input verb is a single tap (`HourlyTouchZoneMapper` / `HourlyBottomZoneHelper`) —
so cycle membership *is* reachability there. Desktop keeps a continuous wheel zoom
(`MIN_BACK_HOURS = 2` … `MAX_BACK_HOURS = 720`) and reaches multi-day spans regardless.

## Design decisions

1. **Rename `ZoomStage.THREE_DAY` → `TWO_DAY`**, keeping it at ordinal 2. Declaration order stays
   load-bearing (`ZoomStage.kt:19`). Legacy widget state persisted as the *string* `"THREE_DAY"`
   fails `entries.find { it.name == raw }` in `WidgetPresentationStateStore.decodeZoom` and falls
   back to `WIDE` — which is exactly what we want, since the stage is now off by default.
2. **Window = 42 back / 6 forward.** The forward horizon is deliberately identical to `WIDE`'s 6 h,
   so zooming out from the default extends history only and does not move the right edge. The
   now-line therefore sits ~87 % across the graph. `navJump = 8` (a sixth of 48, matching
   `HourlyZoomRules.navJumpHours(48)`); `smoothIterations = 3`.
3. **The setting gates the cycle, not the desktop wheel.** Gating a continuous zoom factor on a
   stage toggle would be incoherent and would break the 30-day history browse. So on desktop with
   the setting off, *clicking* cycles `WIDE ↔ NARROW` while the *wheel* still reaches 48 h and
   beyond. On Android, off means genuinely unreachable.
4. **Unify the date-footer boundary in `:shared`.** Today Android keys date mode on
   `zoom.stage == THREE_DAY` (three handlers) while desktop keys it on
   `isDateMode(span > DATE_LABEL_SPAN_THRESHOLD_HOURS = 48)`. A 48 h stage falls on the wrong side of
   `> 48`, so the two platforms would disagree on the *same* window. Fix at the root: one shared
   span-based predicate, `>= 48`. Only a span of exactly 48 h changes behaviour on desktop.
5. **Ghost-line labels stop where dates start.** `GhostLineLabel.LABEL_MAX_SPAN_HOURS` is documented
   as *being* that boundary but is currently an independent `48L` compared with `>`. Derive it
   (`threshold - 1 = 47`) so the 2-day view is excluded, as the 3-day view was, and the existing
   inclusive test at `GhostLineLabelTest.kt:114` still holds.
6. **Stranded state.** Enabling the setting, cycling to `TWO_DAY`, then disabling it must not leave a
   widget pinned to an unreachable stage. Add a pure shared resolver and apply it on read.

## Changes

### `:shared`

- `graph/ZoomStage.kt`
  - `THREE_DAY` → `TWO_DAY`; window becomes `backHours = 42, forwardHours = 6, navJump = 8,
    labelInterval = 6, smoothIterations = 3`. Update the ordinal/order kdoc.
  - `next()` takes `multiDayEnabled: Boolean` (default `false`): `WIDE → NARROW`,
    `NARROW → if (multiDayEnabled) TWO_DAY else WIDE`, `TWO_DAY → WIDE`.
  - New `companion` helper `resolve(stage, multiDayEnabled)`: coerces `TWO_DAY → WIDE` when disabled.
    `nearestByTotalSpan` is left ungated (desktop snaps the wheel against all stages, then `next()`
    applies the gate).
- `graph/ZoomWindow.kt` (`HourlyZoomRules`)
  - New `const val DATE_FOOTER_MIN_SPAN_HOURS = 48` and `fun isDateMode(totalSpanHours: Long)`.
  - Update the `navJumpHours` kdoc: the sixth-of-span example is now 8 h at 48 h, not 12 h at 72 h.
- `graph/GhostLineLabel.kt` — `LABEL_MAX_SPAN_HOURS = HourlyZoomRules.DATE_FOOTER_MIN_SPAN_HOURS - 1`;
  refresh the two kdoc blocks that name THREE_DAY/72 h.
- `graph/HourlyGraphDefaults.kt`, `graph/HourData.kt` — comment-only THREE_DAY references.

### Android (`:app`)

- `widget/WeatherDisplayPreferences.kt` — `multiDayZoomEnabled()` / `setMultiDayZoomEnabled()`,
  default `false`, alongside `hourlyNarrowSpanHours`.
- `widget/WidgetStateManager.kt` — expose `isMultiDayZoomEnabled()`; apply `ZoomStage.resolve` in
  `getZoomStage`; pass the flag through `cycleZoomLevel`.
- `widget/WidgetPresentationStateStore.kt` — `cycleZoom` takes the flag and forwards it to `next()`.
- `widget/handlers/TemperatureHourDataBuilder.kt:272`, `handlers/PrecipViewHandler.kt:546`,
  `handlers/CloudCoverViewHandler.kt:577` — replace `zoom.stage == ZoomStage.THREE_DAY` with
  `HourlyZoomRules.isDateMode(zoom.totalSpanHours)`.
- `widget/handlers/CloudCoverViewHandler.kt:49` — the smoothing `when` gains the renamed branch.
- `ui/SettingsActivity.kt` + `res/layout/activity_settings.xml` + `res/values/strings.xml` — a
  `SwitchCompat` **inside the existing "Hourly Zoom" card** (`activity_settings.xml:83-147`), placed
  *below* the slider and using the `today_overlay_delta_switch` row pattern
  (`activity_settings.xml:278-283`, wired at `SettingsActivity.kt:173`).

  Grouping decisions:
  - **Same card, not a second card.** One card per section is the existing visual grammar, and it
    makes both controls read as jointly describing the tap cycle.
  - **Slider first.** The slider applies unconditionally (`NARROW` always exists); the switch adds an
    optional extra stop. Also avoids reshuffling an existing control (cf. 82b00058, 4b758ad1).
  - **Copy must be re-scoped.** `hourly_zoom_description` currently reads "…Tap the graph to cycle
    zoom levels; *this sets the tightest one*." Written for a one-control section, that sentence
    becomes ambiguous sitting above two controls. Reword the section description to cover the cycle
    ("Tap the hourly graph to cycle zoom levels. These set which levels the cycle includes and how
    wide the tightest one is."); the slider's own value label already carries its specifics
    (`tools:text="5 hours · scrolls 1 per tap"`), so nothing is lost.
  - **Switch copy names the split**: "Include 2-day view" / "Adds a 48-hour level — 42 hours back,
    6 hours forward." Spelling out 42/6 matters because the right edge does *not* move when zooming
    out; a user expecting a symmetric widening would otherwise read it as a bug.

  Note: this rewords an existing user-visible string rather than only adding new ones.

### Desktop (`:desktop`)

- `DesktopConfig.kt` — `multiDayZoomEnabled: Boolean = false` in `DesktopSettings`, plus its `add(...)`
  diff line and the settings-key allowlist at `DesktopConfig.kt:232`.
- `DesktopGraphUtils.kt` — `DATE_LABEL_SPAN_THRESHOLD_HOURS` / `isDateMode` delegate to the shared
  rule; `forwardAnchors` (`:111`) anchors on `TWO_DAY`.
- `Main.kt:1169` — pass the setting into `next()`; `resnapNarrowZoomAfterSpanChange` kdoc rename.
- `SettingsWindow.kt:193` — matching toggle grouped into the existing Hourly Zoom section, same
  order (slider then switch) and same re-scoped copy as Android (dual-platform parity, per 5178bb8e).

## Flagged consequence: desktop's forward-hours curve goes flat

`DesktopGraphUtils.forwardAnchors` pins the continuous forward-span curve to the fixed stages'
forward hours. Today those anchors are WIDE (6 h) and THREE_DAY (24 h), giving a rising curve.
With `TWO_DAY` at **6 h forward**, both anchors carry the *same* value, so the curve is dead flat at
6 h from WIDE's factor (~0.30) up to TWO_DAY's (~0.52), then climbs steeply to
`MAX_FORWARD_HOURS = 168` above it. Wheeling out on desktop will hold the right edge fixed for a
long stretch and then open up abruptly.

This follows directly from the requested 42/6 split and is arguably correct — it is the continuous
expression of "zooming out buys history, not forecast" — but it is a visible change to desktop wheel
feel, not just an Android one. Noting it rather than silently reshaping the curve. If it reads badly
in practice, the fix is a third interior anchor, not a change to the stage.

## Testing

- `shared` — `ZoomStageTest`: cycle order under both flag values; `TWO_DAY.window()` is 42/6/48;
  `nearestByTotalSpan(48) == TWO_DAY`; `resolve` coerces when disabled; ordinal 2 preserved.
  New `HourlyZoomRules.isDateMode` boundary test at 47/48/49.
- `shared` — `GhostLineLabelTest`: existing inclusive-boundary assertions must still pass at 47.
- `app` — `ZoomCycleRoboTest`: with the setting off the cycle is `WIDE ↔ NARROW`; with it on the
  third stop returns; a widget persisted on `TWO_DAY` with the setting off reads back as `WIDE`.
  `WidgetStateManagerTest`, `HourlyZoomSpanSettingRoboTest` updated for the new pref.
- `app` — a Robolectric assertion that the footer switches to date labels at the 2-day window
  (currently implied by the stage check; make it span-driven).
- `desktop` — `DesktopGraphZoomTest` / `NarrowSpanResnapTest` / `RepairStaleNarrowZoomFactorTest`
  for the renamed stage and the new anchor; add a case pinning `forwardHoursFor` at the TWO_DAY
  factor to 6.
- Legacy-decode test: persisted string `"THREE_DAY"` decodes to `WIDE`.

## Out of scope

Removing the now-dead-on-Android multi-day render branches. With the setting present they are
reachable again, so they stay live on both platforms.
