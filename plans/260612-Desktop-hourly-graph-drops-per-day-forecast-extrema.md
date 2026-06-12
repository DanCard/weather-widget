# Fix: desktop hourly graph drops per-day forecast extrema (actual-series labels absorb them)

## Context

On the desktop hourly temperature graph, several per-day **forecast** extrema are not labeled,
though Android labels them:
- forecast low between Wed 10 and Thu 11
- forecast low between Thu 11 and Fri 12
- forecast high on Wed 10 (the orange peak under the pink "92.8°" actual high)

### Root cause (log- and visually-confirmed)

The dense-label thinning `GraphLabelPlacementUtils.filterDenseLabelCandidates` runs over **all**
candidate indices using the **forecast** value series (`labelTemps`). ACTUAL-series anchors
(ACTUAL_HIGH/LOW/END) are `immovable` so they're never removed — but they still act as *absorbers*:
a nearby forecast/LOCAL candidate within `NEARBY_LABEL_WINDOW` (=4 indices) and within the
declutter thresholds (`DENSE_TEMP_DIFF_THRESHOLDS=[3,4,5]`) of an actual anchor gets removed. Desktop
logs:
```
idx=29 forecast VALLEY 63° ← absorbed by idx32 ACTUAL_LOW   (Wed→Thu low)
idx=17 forecast PEAK   84° ← absorbed by idx18 ACTUAL_HIGH  (Wed high, the 92.8° peak)
idx=54 forecast PEAK   64° ← absorbed by idx56 actual/fetch-dot
```
But the actual anchor at idx32 *displays the actual value* (60.6°), not the forecast 66° the thinning
compares against — forecast and actual are **different series** (the accuracy-comparison feature
exists to show both). Treating them as mutually redundant drops the forecast extreme.

**Why Android differs:** Android's hourly view has ~529 points vs desktop's 77 for the same span, so
4 index units is a tiny time slice on Android (forecast/actual extrema never land within the window)
but ~3-4 hours on desktop. So the same shared logic over-thins only at desktop's low point density.
The cross-series absorption is the actual defect; the density difference just exposes it.

### Intended outcome

Forecast/LOCAL extrema are decluttered only against **same-series (forecast)** neighbors, never
against actual-series anchors. Desktop then labels the per-day forecast highs/lows like Android; no
change on Android (cross-series pairs aren't within the window there → already shown).

## Approach

Make ACTUAL-series anchors **non-absorbing** in the dense thinning: they stay retained (never
removed) but cannot cause removal of a nearby candidate. Add an opt-in parameter rather than
changing default behavior.

## Changes

### 1. `shared/src/main/kotlin/com/weatherwidget/shared/graph/GraphLabelPlacementUtils.kt`
- `filterDenseLabelCandidates(...)`: add `nonAbsorbingAnchors: Set<Int> = emptySet()`.
- In the `competingRetained` search (the `nearbyRetained.firstOrNull { otherIdx -> ... }` block,
  ~line 105), skip any `otherIdx in nonAbsorbingAnchors` so those indices never become the
  `competingRetained` that removes a candidate. They remain in `retained` (still kept/drawn).
- Default-empty keeps all existing callers and `GraphLabelPlacementUtilsTest` byte-identical.

### 2. `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt`
- In `collectLabelCandidates`, before the `filterDenseLabelCandidates` call (~line 130), compute the
  set of **actual-displaying** anchor indices from the deduplicated set:
  `deduplicatedIndices.filter { resolveExtremaRole(it, extrema, hours) in {ACTUAL_HIGH, ACTUAL_LOW, ACTUAL_END} }`.
  Using `resolveExtremaRole` (its existing priority order) naturally excludes any index that is also
  a forecast global extreme (those resolve to HIGH/LOW/START/END and *do* display a forecast value,
  so they should keep absorbing).
- Pass that set as `nonAbsorbingAnchors`.

No other suppression stages need changing for the reported cases (the surviving forecast LOCALs clear
`checkRedundantPairSuppression`: its cross-series `actualCandidates` arm uses a 2° threshold and the
three cases differ by ≥2.4°). Note for later: that 2° cross-series arm is the same conceptual issue
in miniature — leave it unless a <2° case is reported.

## Tests

- **Add** to `shared/src/test/kotlin/.../graph/GraphLabelPlacementUtilsTest.kt`: a case where a
  forecast extreme sits within `NEARBY_LABEL_WINDOW` and the declutter threshold of an immovable
  anchor — assert it is removed when that anchor is a normal absorber, and **retained** when the
  anchor is passed in `nonAbsorbingAnchors`.
- **Preserve**: existing `GraphLabelPlacementUtilsTest` and the resolver/label suites stay green.

## Verification

1. `./gradlew :shared:test` — new test green, no regressions.
2. Rebuild + restart desktop (`scripts/buildStart.sh`), open popup
   (`touch ~/.local/share/weather-widget/.show`), screenshot the full hourly graph: the Wed forecast
   high (orange peak under 92.8°) and the Wed→Thu / Thu→Fri forecast lows are now labeled. Confirm in
   the desktop log that `LabelAccepted: role=LOCAL idx=…` now appears for those indices and the
   `labelCandidateFiltered` lines for them are gone.
3. Spot-check the emulator (`adb -s emulator-5554`) is unchanged — its forecast extrema were already
   labeled (cross-series pairs aren't within the window at 529-point density).

## Notes
- Scope is the dense-thinning absorber rule only; no change to point density, window sizing, or
  placement geometry.
- Memory: [[hourly_label_pipeline_index_keyed]], [[per_day_actual_extrema_labels]],
  [[coincident_forecast_inner_side]], [[desktop_label_placement_divergence]].
