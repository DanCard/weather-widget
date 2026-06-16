# Session Log — Hourly Graph: Per-Day Actual Labels + Left-Edge Start Ordering

**Date:** 2026-06-11
**Scope:** `:shared` (cross-platform label engine), verified on Android emulator (`:app`)
**Branch:** main (changes uncommitted at end of session)

---

## Summary

Three connected, emulator-driven refinements to the hourly temperature graph's label pipeline, all
landing in the shared `TemperatureExtrema` / `TemperatureLabelResolver` / `TemperatureLabelEngine`:

1. **Per-day actual extrema labels** — the actual (pink) line labeled only ONE global high/low, so
   in a multi-day view today's actual low went unlabeled when an earlier day was colder. Now each
   visible day's actual high AND low is labeled.
2. **Drop midnight-straddle slope shoulders** — the per-day grouping introduced a redundant "low"
   on the day that loses an overnight valley to the calendar boundary. Filtered out via a
   turning-point test (with the observation-cutoff endpoint exempted).
3. **Left-edge start pair ordering + leader removal** — the left-edge `START` (forecast) and the
   nearest actual label could read inverted (cooler above warmer); reordered by temperature (warmer
   above), and exempted the pair from curve avoidance so they sit flush with no leader lines.

Final state: full `:shared` suite green (110 tests), app rebuilt/installed, all three behaviors
verified on the emulator via `ACTION_REFRESH` broadcast + screenshots + placement logs. Debug
breadcrumbs (`ACTUAL_DAILY …`, `LabelPlacementDebug`) intentionally retained for a few days of
monitoring. Physical phones untouched (emulator-targeted throughout).

---

## Chronological prompts (verbatim) and what was done

### 1. User
> Emulator: 3 day hourly graph view. Today's low for actuals not labeled.

**Response:** Followed the project's "pull live evidence, don't just trace code" rule: captured an
emulator screenshot + `TempLabelResolver`/`TempExtrema` logcat. Root cause confirmed —
`TemperatureExtrema.compute()` computes a single global `actualHighIndex`/`actualLowIndex` over the
whole actual region (`0..actualEndIndex`); logs showed `ACTUAL_EXTREMA lowIdx=227` only. In a 3-day
view the pink line spans Tue→Thu, so the single `ACTUAL_LOW` landed on the globally-coldest valley
(55.6° Tue/Wed) and today's (Thu) warmer valley got only a gray forecast `LOCAL` label. Asked a
scope question.

### 2. User (AskUserQuestion: fix scope)
> Per-day actual low+high

**Response:** Implemented per-day actual extrema in `:shared`:
- `TemperatureExtrema.ExtremaIndices` gained `actualDailyHighIndices` / `actualDailyLowIndices`,
  computed by grouping `actualIndices` by `dateTime.toLocalDate()`.
- `buildPotentialAnchors` anchors each per-day index; `resolveExtremaRole` switched to `in
  extrema.actualDaily*Indices ->` membership (kept after the global daily-high/low/START/END cases).
- Generalized `addCoincidentActualHigh` → `addCoincidentActuals(...)`, now called for BOTH highs and
  lows (with `FORECAST_HIGH_ROLES`/`FORECAST_LOW_ROLES`) so a per-day actual extreme coincident with
  the global daily high/low still gets its own pink label. (This also closed the previously-noted
  "ACTUAL_LOW coincident case unhandled" gap.)
- No placement-engine change needed — it already styles/stacks any number of pink anchors; per-day
  actual anchors are non-LOCAL explicit anchors, hence immovable through `filterDenseLabelCandidates`.
- Tests added to `TemperatureLabelSuppressionTest` (per-day low + per-day high). Verified on
  emulator: `ACTUAL_DAILY lowIdxs=[155, 227, 518]`, today's 60.3° now labeled.

### 3. User
> Emulator: 3 day hourly graph view: There is a 60 degree label on actual line between Tue 9 and Wed 10. It feels unneeded.

**Response:** That 60° was Tuesday's new per-day actual low (idx 155). But it wasn't a real valley —
the genuine overnight low (55.6°) sits just after midnight (Wednesday's calendar day), so Tuesday's
"low" was a monotonic slope point at the day boundary. Added a turning-point filter
(`isActualLocalMin`/`isActualLocalMax`) so a per-day extreme is kept only if both neighbors sit on
the same side. **Exempted the actual-region end** (`i >= actualEndIndex`, the observation cutoff /
NOW) so a real boundary extreme isn't dropped — this exemption was required because a pre-existing
test (`FORECAST_HIGH is suppressed when redundant near ACTUAL_HIGH`) has the observed high sitting
exactly at `actualEndIndex`. Added a slope-shoulder regression test. Verified: `ACTUAL_DAILY
lowIdxs=[215, 506]` — the Tuesday slope label gone, today's low retained.

### 4. User
> Emulator: hourly temperature view: left side. Are the 66 forecast label and 66.9 actual temp labels there to document beginning of graph lines? If yes, I would prefer that the higher temp be shown higher. the 66.9 temp should be above the actual graph line and the 66 for forecast graph line should be placed below forecast graph line.

**Response:** Logs identified the two labels: `START` idx 0 (forecast 66.0°, placedAbove=true) and
`ACTUAL_LOW` idx 6 (actual 66.9°, placedAbove=false) — warmer value below the cooler, i.e. inverted.
They're placed independently on opposite sides of their (nearly-coincident) lines:
`prefersAbovePlacement` puts START above (66° is the local max as the forecast descends),
`computeForcedAboveLowIndices` only flips an actual low above when a lower low is nearby. Asked a
scope question.

### 5. User (AskUserQuestion: scope)
> Left-edge start pair only

**Response:** Added `computeLeftEdgeStartOrdering(candidates)` to `TemperatureLabelEngine`: finds the
`START` candidate + nearest actual-role candidate within `LEFT_EDGE_START_WINDOW` (8 idx) and returns
an index→placeAbove map ordering them by value (warmer above). Wired as the highest-priority branch
of the `preferAbove` `when`. Verified in-app: 66.9° actual moved above, 66.0° forecast below.

### 6. User
> Both labels have leader lines, and it seems to me neither need leader lines. Should we relax the need for leader lines and or make the leader lines shorter?

**Response:** The leaders were a direct side effect of the reorder — putting each label on its
curve-grazing side triggered a 1-step displacement (`drawLeaderLine = step > 0`). Logs:
`ExactFitPreCheck` showed the actual line spiking into the actual-low box and the descending forecast
line cutting the START box. Fix: exempt the left-edge pair from curve avoidance (`isLeftEdgePair`:
skip `tryExactFitCurveAvoidance`, force `overlapsCurve=false`) so they place flush at step 0 → no
leader. Safe because at the edge each label is unambiguously tied to its color-matched line start.
This ALSO fixed the unit test, which was failing for the same reason (curve avoidance fighting the
forced direction). Verified: both labels `displacementSteps=0`, no leaders, ordering preserved.

### 7. User
> 110 shared tests: 1 failed. ✗ TemperatureLeftEdgeStartOrderTest > left-edge start pair is ordered by temperature - warmer actual above cooler forecast

### 8. User
> Test still failed: ... TemperatureLeftEdgeStartOrderTest ...

**Response:** Two synthetic-fixture bugs, fixed in sequence: (a) `forecast[0]` was the global
forecast max, so idx 0 resolved to `HIGH` not `START` — moved the daily high downstream
(`forecast[18]=90`); (b) the symmetric synthetic valley made curve avoidance reject the forced
"above" — resolved by the curve-avoidance exemption from prompt 6. Added an explicit
`!drawLeaderLine` assertion to lock in the leader-free placement. Full `:shared` suite green.

### 9. User
> write sesson log to session-logs/ dir

**Response:** This file.

---

## Files changed

- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureExtrema.kt`
  - `ExtremaIndices` + `actualDailyHighIndices`/`actualDailyLowIndices`; per-day grouping with
    turning-point filter (`isActualLocalMin/Max`, endpoint-exempt).
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt`
  - per-day anchors; `resolveExtremaRole` membership; `addCoincidentActuals` generalized to high+low
    (`FORECAST_HIGH_ROLES`/`FORECAST_LOW_ROLES`).
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelEngine.kt`
  - `computeLeftEdgeStartOrdering` + highest-priority `preferAbove` override; `isLeftEdgePair`
    curve-avoidance exemption (no leaders).
- Tests: `TemperatureLabelSuppressionTest.kt` (per-day low/high + slope-shoulder),
  new `TemperatureLeftEdgeStartOrderTest.kt`.

## Key learnings / gotchas

- Grouping actual extrema by calendar day has a midnight-straddle edge: the losing side gets a
  monotonic slope "extreme." Require a genuine turning point; exempt the observation-cutoff endpoint.
- The left-edge leader lines and the failing test shared ONE root cause — curve avoidance fighting a
  forced placement direction. One scoped exemption fixed both.
- Synthetic placement-test fixtures: ensure idx 0 isn't the global forecast max, or it resolves to
  `HIGH` instead of `START`.
- `resolveExtremaRole` checks global daily high/low BEFORE the actual-membership cases, so a per-day
  actual extreme coincident with the global daily extreme needs the `addCoincidentActuals` injection.

## Verification

- `./gradlew :shared:test` — 110 tests green.
- `./gradlew installDebug`; on `emulator-5554`, `adb shell am broadcast -a
  com.weatherwidget.ACTION_REFRESH -p com.weatherwidget`; screenshots + `LabelPlacementDebug` /
  `ACTUAL_DAILY` logcat confirmed each behavior.

## Follow-ups

- Desktop has a separate, simpler label reimpl and doesn't construct `ExtremaIndices`, so none of
  this crosses over — port if desktop parity is wanted.
- Remove the temporary `ACTUAL_DAILY` / resolver debug logging after a few days of monitoring.
- Changes uncommitted; can be committed as one set or split (per-day labels vs left-edge ordering).
