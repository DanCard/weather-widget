# 2026-06-12 — Hourly temperature-graph label-placement fixes

A run of six related fixes to the **hourly temperature graph** label placement, all driven by live
reports on the Samsung device, the Android emulator, and the Linux desktop app. The graph draws a
forecast curve and an observed ("actual", pink `#FF3366`) curve; small temperature labels mark
extrema and a few anchors. All placement logic now lives in the **shared** module
(`com.weatherwidget.shared.graph`), used by both Android (`TemperatureGraphRenderer`) and desktop
(`TemperatureGraph`), so each fix lands once and benefits both platforms.

Key shared files touched this session:
- `TemperatureLabelEngine.kt` — per-candidate placement loop (above/below, curve avoidance, leaders)
- `TemperatureLabelResolver.kt` — which indices become label candidates, suppression/dedup
- `GraphLabelPlacementUtils.kt` — `filterDenseLabelCandidates` (decluttering)
- `TemperatureExtrema.kt` — per-day / global extrema index computation

Debugging method throughout: pull `LabelPlacementDebug` / `TempLabelResolver` / `TempExtrema` lines
from `adb logcat` (Android) or `~/.local/state/weather-widget/*.log` (desktop), crop device/desktop
screenshots, map the labels to indices, then fix the shared engine and re-verify live.

---

## 1. Forecast LOW drawn on top of the fetch-dot value label ("631°")
**Commit:** `9146675c` + `00ff9b0c` (Robolectric e2e). Report: Samsung "clash of low temp labels".

**Symptom:** garbled "631°" at the valley near NOW — an orange forecast LOW "63°" overlapping the
pink fetch-dot value "61°".

**Root cause (logs):** the real `ACTUAL_LOW` was *suppressed* (`reason=FETCH_DOT`) because the fetch
dot already shows that observed value; the pink number is the **fetch-dot value label**. The forecast
`LOW` at the adjacent hour was placed `reason=below, displacementSteps=0` with **no collision
detected** — the engine was blind to the fetch-dot value label. On Android the dot bounds were
computed (`computeFetchDotBounds`) but added to `ctx.drawnLabelBounds`, which is **not** passed into
`computePlacements` (only `drawnIconBounds` is). On desktop the rect *was* passed but valley
minor-overlap allowance let the low graze it.

**Fix:** new `reservedHardBounds: List<GraphRect>` parameter on `computePlacements`, checked as a
HARD collision (never softened by minor-overlap) in the three placement gates (main loop,
`checkExactFitBlockers`/exact-fit re-check, `tryValleyBelowCascade`). Android routes
`fetchDotPreBounds`; desktop collects the value/age rects. Default empty → all other callers/tests
unchanged. The valley LOW now flips ABOVE the curve when its below-slot is occupied.

**Tests:** `TemperatureLabelFetchDotHardBoundsTest` (control reproduces, fix flips) + a Robolectric
end-to-end guard in `TemperatureGraphLabelPlacementRobolectricTest` that the Android call-site wiring
is connected. Verified live on Samsung + desktop.

---

## 2. "91°" forecast high drew a long leader line under the actual peak (desktop)
**Commit:** `a80373e4` (first half). Report: desktop "91 should be on top of curve, leader not needed".

**Symptom:** the Thu forecast HIGH "91°" (under the pink "97.7°" actual high) was pushed far up with
a long vertical leader line — and still grazed the towering pink curve.

**Root cause:** forecast `HIGH` (91) and `ACTUAL_HIGH` (97.7) were at the **same index**, the forecast
peak nested inside the much-taller actual curve. Curve avoidance climbed the label up alongside the
pink curve → `above+curveFit`, `displacementSteps=1`, leader.

**Fix (first iteration):** `computeCoincidentForecastExemptIndices` — when a forecast HIGH/LOW shares
its index with a strictly-more-extreme `ACTUAL_HIGH/ACTUAL_LOW`, keep its conventional side but exempt
it from curve avoidance so it sits flush on its OWN line. *User clarified they want the label ABOVE
its own forecast line (not buried on the fill/inner side); an earlier inner-side version was rejected.*
This iteration was later **superseded** by fix #5 (it only matched the exact same index).

**Tests:** `TemperatureCoincidentForecastInnerSideTest` (HIGH above, LOW below, no leader).

---

## 3. ACTUAL_LOW "60.6°" flipped above its valley on its own line's graze (desktop)
**Commit:** `a80373e4` (second half). Report: desktop "Thu 11: 60.6 should be below the curve".

**Symptom:** the ACTUAL_LOW "60.6°" was drawn ABOVE the pink valley (cramped, with a short leader)
when clean space sat below — like the fetch-dot value "61.2°" beside it.

**Root cause:** the 260609 rule "ACTUAL_LOW flips above when a curve intrudes its below-box" used
`combinedCurveIntrusion` (actual + forecast merged). The original plan *assumed* "any intrusion below
is the forecast curve" (actual curve can only be at `Y ≤ sy`). False: the observed line has
sub-hourly / smoothed points that dip a few px below the labeled hourly minimum, so the label's OWN
pink line grazed its below-box and tripped the flip. User confirmed the forecast was far away.

**Fix:** per-candidate `avoidanceActualPoints = if (role == ACTUAL_LOW) emptyList() else
actualVisiblePoints`, threaded into the main-loop `curveIntrusion` and `tryExactFitCurveAvoidance`.
ACTUAL_LOW now avoids only the FORECAST curve, so the below-block fires only on real forecast
intrusion (its documented intent). Forecast-far → below; forecast-dips-below → still above (Samsung
case preserved).

**Tests:** `TemperatureActualLowOwnCurveGrazeTest` (forecast-far → below; forecast-dips → above).
Verified live (logs: `placeAbove=false intrusion=none`).

---

## 4. Desktop dropped per-day forecast extrema (actual anchors absorbed them)
**Commit:** `5cafeb6b`. Report: desktop "forecast low between Wed/Thu & Thu/Fri, and Wed forecast high, not labeled; works on Android".

**Symptom:** on desktop the Wed forecast high (84°, the orange peak under the pink 92.8°) and the
Wed→Thu / Thu→Fri forecast lows were unlabeled; Android showed them.

**Root cause:** `filterDenseLabelCandidates` declutters all candidate indices using the FORECAST value
series. ACTUAL-series anchors are `immovable` (never removed) but still *absorb* a nearby forecast/
LOCAL extreme within `NEARBY_LABEL_WINDOW` (=4) and the declutter thresholds `[3,4,5]`. e.g. forecast
valley idx29 (63°) was absorbed by retained ACTUAL_LOW idx32 — but idx32 *displays* the actual 60.6°,
a different series. Forecast vs actual are the accuracy-comparison pair; both should show. **Why
Android was fine:** ~529 points vs desktop's 77 for the same span, so 4 index-units is a tiny time
slice (forecast/actual extrema never land within the window). Desktop's low point density exposed the
latent cross-series defect.

**Fix:** new `nonAbsorbingAnchors: Set<Int>` param on `filterDenseLabelCandidates` — those indices
stay retained but are skipped when picking the `competingRetained` that removes a candidate. Resolver
computes them as deduplicated indices whose `resolveExtremaRole` ∈ {ACTUAL_HIGH, ACTUAL_LOW,
ACTUAL_END} (priority order naturally excludes coincident forecast-global indices, which keep
absorbing). Default empty → CloudCover/Precip callers and Android unchanged.

**Tests:** `GraphLabelPlacementUtilsTest` "non-absorbing anchor does not declutter…" (control absorbs,
fix retains). Verified live: desktop `Filtered` list regained idx 16/28/53/64.

---

## 5. Forecast highs drew long leader lines under taller actual curves (Android emulator)
**Commit:** `c08513ec`. Report: emulator "long leader lines for 84 and 91 forecast highs aren't helpful".

**Symptom:** on the emulator, "84°" and "91°" (and "63°", "67.6°") forecast labels were pushed up/down
with long leader lines.

**Root cause:** same nested-under-taller-actual case as fix #2, but at Android's 7× point density the
coincident actual extreme lands 2–7 indices away (logs: `HIGH idx=358` with `ACTUAL_HIGH idx=356`;
`LOCAL idx=90 (84°)` with `ACTUAL_HIGH idx=97`). The exact-same-index `computeCoincidentForecastExemptIndices`
from fix #2 missed them → curve avoidance re-introduced the leader (`above+curveFit(16.5px)`).

**Fix (generalization):** **forecast-series labels avoid only the forecast curve, never the actual
curve** — the resolution-independent generalization of fix #3's ACTUAL_LOW carve-out. Added
`FORECAST_ONLY_AVOIDANCE_ROLES = {HIGH, LOW, LOCAL, FORECAST_HIGH/LOW, PAST_FORECAST_HIGH/LOW}` and
extended `avoidanceActualPoints` to cover them. A forecast peak nested under any taller actual sits
flush on its own peak, regardless of index gap. This **subsumed and removed**
`computeCoincidentForecastExemptIndices` and the engine-local `FORECAST_HIGH_ROLES`/`FORECAST_LOW_ROLES`
(net: less code). START/END/ACTUAL_END keep full avoidance; ACTUAL_HIGH still uses
`placeActualHighAboveCurve`.

**Tests:** `TemperatureCoincidentForecastInnerSideTest` extended with non-coincident-index variants;
it remained the primary regression gate (still passes via the general rule). Verified live on emulator
(84°/91° now `reason=above displacementSteps=0`) and desktop.

---

## 6. Incomplete current-day's morning bump labeled as its actual high ("67.6°")  — UNCOMMITTED
Report: emulator "67.6 mid-day label not helpful when multiple days are shown". User clarified: it's
NOT the third day's high — the third day hasn't reached its high yet; only two shown days have peaked.

**Symptom:** "67.6°" (`ACTUAL_HIGH`, the day's observed morning max) labeled mid-graph between the two
tall peaks. `ACTUAL_DAILY highIdxs=[97, 356, 434]` → the third entry (434, 67.6) is the current,
incomplete day (Fri 12) whose real high is still ahead in the forecast.

**Root cause:** `TemperatureExtrema.compute` labels each calendar day's observed max as its
`ACTUAL_HIGH`, including the in-progress day. A morning bump passes `isActualLocalMax` even though the
afternoon (real high) hasn't been observed.

**Fix:** `dayHighReached(hi)` — for the day of `actualEndIndex` (only when `transitionX != null`), drop
the day's actual high when the forecast for the REMAINDER of that same day climbs more than
`INCOMPLETE_DAY_HIGH_MARGIN_DEGREES` (**5°F**) above the observed max. Completed/past days always keep
theirs. The 5° margin is the crux: it's above a normal forecast-vs-actual peak gap (a few degrees,
where both labels are wanted) but well below the egregious morning-bump gap (12°+ here), so only a
clearly-unreached high is dropped.

**Tests:** new `TemperatureExtremaIncompleteDayTest` (suppress at 18° gap, keep at −1° gap). Two
`TemperatureLabelSuppressionTest` cases were retimed (`start` late) so their observation cutoff lands
on a day boundary — making them realistic *completed*-day scenarios rather than incidentally tripping
the new rule. The Robolectric "actual & forecast highs differ" test (2.8° gap) drove the margin choice
up from an initial 2° to 5°. Verified live: `ACTUAL_DAILY highIdxs=[97, 356]` (third entry dropped),
"67.6°" gone from the emulator graph.

**Status:** committed work ends at #5 (`6733b8c3` also renamed `scripts/build-start.sh` →
`buildStart.sh` and updated doc references). Fix #6 is uncommitted: modified
`TemperatureExtrema.kt` + `TemperatureLabelSuppressionTest.kt`, new `TemperatureExtremaIncompleteDayTest.kt`.

---

## Cross-cutting notes / lessons
- **Two clean, reusable principles emerged:** (a) a label avoids only its *own* obstacle class — an
  actual extreme ignores its own observed line's graze; a forecast label ignores the other series'
  (actual) curve entirely; (b) decluttering and "redundancy" must be **within-series** — forecast and
  actual extrema are the comparison the feature exists for, never each other's redundant duplicates.
- **Point density is the silent variable.** Desktop renders the hourly graph at ~77 points; Android at
  ~529 for the same span. Index-based windows (`NEARBY_LABEL_WINDOW`, exact-index matching) behave very
  differently across platforms. Prefer resolution-independent rules (series-based, pixel/time-budget,
  or value-margin) over raw index counts. Two bugs this session (#4, #5) were latent until desktop's
  low density exposed them.
- **`adb logcat`/desktop-log `LabelPlacementDebug` + cropped screenshots** were decisive every time —
  static code reading produced a wrong hypothesis on #1 (valley cascade) that the logs overturned
  (fetch-dot suppression).
- Desktop restart script is now `scripts/buildStart.sh` (was `build-start.sh`); `fast-desktop-restart.sh`
  only relaunches the existing distributable, so a shared-module change needs `buildStart.sh` to rebuild.
- Verification loop each time: `./gradlew :shared:test` + `:app:testDebugUnitTest --tests
  "*TemperatureGraphLabelPlacement*"`, then `installDebug` + emulator screenshot/logs, then
  `buildStart.sh` + desktop `.show` trigger + screenshot.
