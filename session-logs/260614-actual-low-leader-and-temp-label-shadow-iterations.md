# Session log — 2026-06-14

ACTUAL_LOW leader-line shortening, hourly temp-label shadow + font, and daily-history label
shadow/outline tuning across Android + desktop.

---

## All user prompts (verbatim, in order)

1. **Samsung, hourly temperature graph.** "Samsung, hourly temperature graph.  Low temp on actual line
   has a long leader line.  Can we shorten that leader line?"
   - (mid-work): "Partial overlap with actual forecast line is o.k."

2. "The change looks good on android"

3. (`/config` dismissed) then:
   "1) the now temperature isn't easy to read when overlaps forecast graph line or now line.  Maybe
   add a shadow?  Can use same shadow algorithm that is used for daily forecast history high temp
   labels.  Not sure if this should be for all labels.
   2) Lets increase the font size by 10% for temperature labels in the hourly temperature graph."
   - Clarifying answers: shadow on **all temp value labels**; +10% on **temp values only**; **Android + desktop**.

4. "I don't see shadow on android"

5. (interrupt) "Disregard, I see it now, had to zoom in. The shadow on desktop seems too strong, for
   both the daily forecast view and hourly temp labels.  Any thougths to reduce the shadow?  On
   android it looks good.  Maybe dup the android algo to desktop?"

6. "Daily forecast view: I see two problems on android and one on desktop.  Android issues:
   1) Shadow seems heavier than what I saw 15 minutes ago.
   2) Shadow affects today and future days.  That is not suppose to happen.  Seemed fine 15 minutes
   ago.  Only suppose to happen for history high temp.  Not today or future days.
   Desktop:
   3) Desktop shadow isn't strong enough.  Would be nice if it was closer to android
   What do you suggest for next steps?"
   - (interrupt) "I'd like to revert the most recent changes only to see if that reverts the android
     changes I'm seeing."
   - (interrupt) "Was android daily forecast view shadow changed.  It should not have."

7. "Thanks that fixed my biggest concerns on android.  New tweak desired: Shadow on both desktop and
   android not strong enough.  Daily forecast view, history.  Not sure if it should be applied to
   hourly temp graph also."
   - Clarifying answers: **thin crisp outline**; **history only** (no shadow on today/future); **leave hourly as-is**.

8. (injected during planning) "long tests: 1 failed.
   ✗ DailyGapFallbackGraphIntegrationTest > renderGraph uses yellow for today actual bar and orange
   for today snapshot bar"

9. "continue" / "Continue from where you left off."

10. "A little bit hard to see on desktop, android is fine.  Can we increase black background size on
    desktop?"

11. "There is a 2% shrink when two labels on daily forecast view history.  Lets remove that on
    desktop.  Replace with a 4% font size increase for lower label, the one that has a forecast bar
    going through it."

12. "write session log to session-logs/ dir .  Include all prompts"

13. "Increase lower label font size from 4% boost to 8% boost."

---

## Task 1 — Shorten ACTUAL_LOW leader line (hourly graph)

**Problem:** On the hourly graph, the actual (pink) line's LOW label was pushed far from its valley
with a long leader line, because the forecast curve dipping below the valley blocked the natural
below-placement and forced the label above. User okayed partial overlap with the forecast curve.

**Fix** — `shared/.../graph/TemperatureLabelEngine.kt`:
- Added `ACTUAL_LOW_FORECAST_OVERLAP_RATIO = 0.5f` + `allowedDipPxFor(role, density, labelHeight)` —
  ACTUAL_LOW tolerates half a label-height of forecast overlap; other roles keep the 5dp default.
- Three coordinated edits so the curveFit path and the main step-loop agree on tolerated overlap:
  (1) `tryExactFitCurveAvoidance` uses the role-aware dip; (2) the blanket ACTUAL_LOW below-block now
  only flips above when `dipDepth > allowedDipPx`, else defers to the main loop (GAVE_UP); (3) main
  loop `overlapsCurve` gate gains `curveWithinDip` (scoped to ACTUAL_LOW).
- Regression test added to `TemperatureActualLowOwnCurveGrazeTest.kt` ("stays below with no leader
  when the forecast only shallowly grazes"). Gotcha learned: synthetic tests need a MONOTONIC forecast
  graze — a sharp forecast V bezier-overshoots below its vertex AND spawns a competing same-index
  FORECAST_LOW, both flipping the label above.

**Verify:** `:shared:test` green; `:app` `TemperatureGraphLabelPlacementRobolectricTest` green. User
confirmed good on Android. (Committed by user as part of `7dfd31de`.)

---

## Task 2 — Hourly temp-label shadow + 10% font

Decisions: strong shadow on **all temp value labels**; +10% font on **temp values only**; both platforms.

**Android** `TemperatureGraphStyle.kt` + shared `HourlyGraphDefaults.kt`:
- Added shared `COLOR_SHADOW_SOLID=0xFF000000`, `TEMP_LABEL_SHADOW_RADIUS_DP=2.5`, `TEMP_LABEL_SHADOW_DY_DP=1.0`.
- Applied the solid-black blur to `actualTempLabelTextPaint`, `forecastTempLabelTextPaint`, `valueTextPaint`.
- Font +10%: `TEMP_LABEL_SIZE_DP` 23→25.3, `VALUE_LABEL_SIZE_DP` 23→25.3; decoupled hour labels via
  new `HOUR_LABEL_SIZE_DP=23` (they had shared TEMP_LABEL_SIZE_DP).

**Desktop** `DesktopGraphUtils.kt` + `TemperatureGraph.kt`:
- First pass extracted the daily `drawShadowedText` outline helper to DesktopGraphUtils (later
  superseded — see Task 3). Font: `TEMP_VALUE_LABEL_SP=15.4` (14+10%) in the now-temp value, the
  metrics textStyle feeding the engine, and the on-curve labels. Hour/day/NOW labels unchanged.

---

## Task 3 — Desktop shadow "too strong" → dup Android (then re-tuned)

User: desktop outline too strong, Android good. Switched desktop helper from glyph-outline to an
Android-matched soft Compose blur `Shadow` (`tempLabelShadow(scale)` using the shared
TEMP_LABEL_SHADOW_* constants); `drawShadowedText` re-measures `real.layoutInput.style` with the
shadow added.

---

## Task 4 — Android daily "heavier + on today/future" (diagnosis + revert)

Root cause was **not** this session's hourly/desktop work: commit `bead92c9` (user-authored, between
turns — "Lighten OBSERVED color and add outline stroke to Android temp labels") added a heavy black
outline (`0.32 × fontSize`) inside `DailyForecastGraphRenderer.drawTempLabel()`, which runs for every
daily label (history/today/future). Reverted that outline addition (removed the stroke + the unused
`OUTLINE_STROKE_FRACTION`), keeping the OBSERVED color change. User: "fixed my biggest concerns."

---

## Task 5 — Daily history labels: thin outline, history-only (final)

Decisions: **thin crisp outline**, **history only** (no shadow on today/future), **hourly unchanged**.
Plan: `/home/dcar/.claude/plans/samsung-hourly-temperature-graph-peppy-journal.md`.

**Android** `DailyForecastGraphRenderer.kt`:
- `LABEL_OUTLINE_STROKE_FRACTION = 0.12f`; `drawTempLabel(... drawOutline=false)` draws a thin black
  STROKE before the fill when true. Passed `drawOutline=day.isPast` at low/single-high sites and
  `true` for the past-only dual highs. Removed blur from temp paints (today/future no shadow);
  `rainTextPaint` keeps blur.

**Desktop** `DesktopGraphUtils.kt` + `DailyForecastGraph.kt`:
- Added `drawOutlinedText` (thin Stroke). Daily history → `drawOutlinedText`; today/future → plain
  `drawText`. Soft-blur `drawShadowedText`/`tempLabelShadow` now serve hourly only.

**Test fix** `DailyGapFallbackGraphIntegrationTest.kt`: the prompt-8 failure was a stale hardcoded
`assertEquals(-52378, ...)` (= old #FF3366) after OBSERVED was lightened to #FF7799. Changed to
`assertEquals(WeatherConditionColors.OBSERVED, todayBar.color)`.

**Verify:** `:desktop:test` + daily Robolectric tests green; Android screenshot confirmed crisp thin
outline on history, plain today/future.

---

## Task 6 — Desktop outline too faint → thicken (prompt 10)

`DesktopGraphUtils.OUTLINE_STROKE_FRACTION` 0.12 → **0.18** (desktop labels are smaller/14sp, so the
same fraction read fainter than Android's). Android stays 0.12.

---

## Task 7 — Desktop dual-high label sizing (prompt 11)

`DailyForecastGraph.kt`: removed the 2% two-label shrink (`dualBase = 12f*scale`, dropping
`DualHighLabel.TWO_LABEL_FONT_SCALE=0.98`) and added `LOWER_DUAL_LABEL_FONT_BOOST` applied via
`dualBaseFor(temp, other)` to the LOWER high (smaller temp → sits where the taller forecast bar passes
through it), in both the showBoth room-test and the draw. Android still uses the 0.98 shrink.
Boost started at 1.04f, then raised to **1.08f** (prompt 13).

---

## Files touched this session
- `shared/.../graph/TemperatureLabelEngine.kt` (+ test `TemperatureActualLowOwnCurveGrazeTest.kt`)
- `shared/.../graph/HourlyGraphDefaults.kt`
- `app/.../widget/TemperatureGraphStyle.kt`
- `app/.../widget/DailyForecastGraphRenderer.kt`
- `app/src/test/.../widget/DailyGapFallbackGraphIntegrationTest.kt`
- `desktop/.../DesktopGraphUtils.kt`, `desktop/.../TemperatureGraph.kt`, `desktop/.../DailyForecastGraph.kt`

## Memory written
- `actual_low_partial_overlap_dip.md`, `hourly_temp_label_shadow_and_size.md`,
  `daily_label_outline_history_only.md` (all indexed in MEMORY.md)

## Git note
Some work was committed by the user mid-session (`bead92c9`, `7dfd31de`). Tasks 5–7 + the test fix and
the Android daily revert remained as working-tree changes at end of session (not committed by me).
