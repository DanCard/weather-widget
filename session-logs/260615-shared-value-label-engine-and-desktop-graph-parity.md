# Shared ValueLabelEngine + desktop rain/cloud graph Android-parity pass

## Summary
Started as "the desktop hourly **rain** graph doesn't look as good as Android" and unwound, over
several rounds of visual feedback, into a full deduplication of the `%`-value label placement that
was copy-pasted across four graph renderers.

The arc:
1. **Cosmetic parity** on the desktop precip graph — drop the footer hour labels flush to the
   bottom (0 padding), make the NOW indicator canonical, make the day/night dashed dividers subtler.
   (Already committed: `0190a6ca`…`13f33c6d` lineage — footer/NOW/divider + full-width work.)
2. **Full-width fix** — precip then cloud curves stopped short of the right edge; switched both to
   the data-span `xAtTime` mapping the temperature graph already used.
3. **Missing edge labels** — once the curves reached the edges, low edge values (cloud "1%" at the
   right; rain "0%" at both ends) *vanished*. Root cause: a `safeBottom` buffer (meant to keep
   labels off the footer) was wrongly applied to above-the-curve placements too, so both placement
   attempts were rejected.
4. **The real fix = dedup** — rather than patch the bug in two desktop copies, extracted ONE pure,
   tested `ValueLabelEngine` into `:shared` and routed all four renderers (Android precip + cloud,
   desktop precip + cloud) through it. Porting Android precip's mature algorithm (which already
   handles low edge values) fixed the bug as a side effect.

End state: one shared engine with 8 plain-JUnit tests; full `:shared` and full `:app` unit/Robolectric
suites green (proving the engine is behavior-faithful to the shipping Android widget); desktop edge
labels visually confirmed present.

## Prompts (verbatim, in order)
1. `Desktop hourly rain graph doesn't look good.  Can you make it look more like android?  Perhaps just drop the hourly labels down.  0 padding on the bottom.  Can compare to emulator if that helps.`
2. `now line should look more like android also.`
3. `Why does desktop have dashed vertical lines?  Remove those?`
4. (AskUserQuestion) → `On desktop make them more subtle.  On android they are barely visible.`
5. (plan approved)
6. `yes fix cloud issue also`
7. `Right side doesn't look great on zoom in.  Graph is missing on far right side.`
8. `Cloud graph missing end label.  Rain graph missing start and end rain chance label.  Can compare with emulator if that helps.`
9. `Issue is on desktop`
10. `Not sure what is happening, can I get plan?`
11. `yes apply footer and now parity to cloud` (earlier, during the parity pass)
12. `Shouldn't that code be shared?` / `Shouldn't differ`
13. `Have lots of issues with labels.  Please add tests.  Would it be better to dedup the code now?`
14. (AskUserQuestion) → `Desktop + Android`
15. (plan approved)
16. `continue` / `continue from where you left off`
17. `write session log to session-logs/ dir`

## What was built / changed

### 1. Desktop graph Android-parity (committed earlier in the thread)
- **Footer hour labels** dropped flush to the bottom via the temperature graph's measured-band
  layout (`hourlyFooter()` in `DesktopGraphUtils`), replacing the bespoke `yOffset = h - 22f` float.
- **NOW indicator** routed through shared `NowIndicatorGeometry` (`drawNowLine`/`drawNowLabel`):
  removed the half-size top-pinned "NOW" + white/blue target circles; full-size collision-aware label.
- **Day/night dividers** alpha 0.4 → 0.2 on desktop only (Android already barely visible); divider x
  switched from index-only `stepWidth*i` to `xAt(i)` so it tracks the curve under drag.
- **Footer/NOW extraction**: the footer strip + NOW drawing were pulled into shared `DesktopGraphUtils`
  `DrawScope` helpers (`hourlyFooter`, `drawHourlyFooterStrip`, `drawNowLine`, `drawNowLabel`) and all
  three desktop graphs (temp/precip/cloud) migrated. `painters` unified to dense `points.map{...}`.
- **Full-width fill**: precip + cloud `xAtTime` switched from window-span to **data-span**
  (`points.first()..points.last() → [0,w]`), matching the temperature graph; removed dead `windowSpan`.

### 2. Shared `ValueLabelEngine` (this session's uncommitted work)
- **New** `shared/src/main/kotlin/com/weatherwidget/shared/graph/ValueLabelEngine.kt` — pure,
  Compose/Android-free. `computePlacements(labelSignal, points, geometry, config, measureText,
  textAscent/Descent, dpToPx, drawnIconBounds, firstLabeledPositive, numColumns) ->
  List<ValueLabelPlacement>`. Returns BOTH conventions (centerX+baselineY for Android Canvas;
  `box: GraphRect` top-left for Compose) plus classification flags so platforms rebuild their own
  debug structs. Algorithm = Android precip's (richest): zero-run + first-positive candidates, dense
  thinning, left-edge suppression, value-threshold preferBelow, `isLowPreferredBelow` low-value
  bottom-overflow. Reuses `GraphLabelPlacementUtils` (candidate kind/priority, dense filter, vertical
  placement, suppress-left-edge) + `findLocalExtremaIndices`.
- **`findLocalExtremaIndices(List<Int>, isMax)`** moved into shared `GraphLabelPlacementUtils`
  (canonical home; the engine and Android both use it).
- **`Config.precip()` vs `Config.cloud()`** — the only per-graph differences:
  - cloud: softDip 85/15 (vs 65/8), no zero-run/first-positive, `requireNonZeroExtrema=false`.
  - cloud: `midpointMinColumns=5` (only-edges midpoint injected on wide widgets only) and
    `midpointRequiresDistinctValue=false` (cloud injects a midpoint even on flat 100% data; precip
    won't add a redundant one). **Both flags were forced by cloud's Robolectric tests.**
- **New** `shared/src/test/.../ValueLabelEngineTest.kt` — 8 plain-JUnit tests: low start/end edge
  labels placed (the regression), peak-above, no-overlap, coord consistency, requireNonZeroExtrema,
  left-edge suppression, dense thinning.

### 3. Migrations (this session)
- **Android precip** (`PrecipitationGraphRenderer.kt`): deleted ~108-line candidate block + the
  142-line `calculateProbabilityLabelPlacements`; now one engine call + map `GraphRect →
  ProbabilityLabelPlacement/PrecipRect` and rebuild `LabelPlacementDebug` from the engine's flags.
  Removed 10 now-dead constants + 2 imports.
- **Android cloud** (`CloudCoverGraphRenderer.kt`): replaced the inline-in-`renderGraph` candidate +
  placement block (incl. a stray leftover `println("DBG_CLOUD…")`) with an engine call; repopulates
  `drawnLabelBounds` for the downstream watermark/day-label avoidance. Removed dead constants/imports.
  Left `shouldAllowBottomOverflow`/`shouldAllowIconOverlap` (now unused-by-production) because they
  have direct unit tests.
- **Desktop precip + cloud** (`PrecipitationGraph.kt`, `CloudCoverGraph.kt`): deleted the duplicated
  candidate/soft-dip/placement loops + local `getCurveYAtX` + leader lines; build `LabelTextMetrics`
  from the `TextMeasurer`, call the engine, draw `box.topLeft`. Leader lines dropped (Android never
  had them — intended).

## Key decisions / gotchas
- **Test-guarded migration order** (engine+tests → Android precip → Android cloud → desktop): a green
  Android precip suite *proves* the engine is faithful, far stronger than a screenshot.
- **PrecipRect kept** (testability seam) — engine speaks `GraphRect`, Android converts; do not swap
  PrecipRect for `RectF`.
- The engine deliberately emits **no leader lines** — Android places each label one gap from its
  point, so they're unneeded; desktop matches.
- Two cloud-specific midpoint flags exist only because cloud's Robolectric tests pin that behavior;
  don't "simplify" them away.
- `widthPx`/`heightPx` are `Int` on Android — `.toFloat()` when building `Geometry`.

## Verification
- `./gradlew :shared:test` — green (incl. `ValueLabelEngineTest`).
- `./gradlew :app:testDebugUnitTest` — **full suite green**, every precip + cloud renderer test
  unchanged.
- Desktop visual: cloud "1%" end label and rain "0%" start+end labels now present (off-screen window
  captured via `xwininfo` id → `import -window <id>` → jpg). Config restored to daily default; desktop
  app running on a clean debug-free build.
- Android emulator screenshot skipped — `adb` hangs on the multi-device setup; Robolectric (renders to
  a real Canvas) makes it redundant.

## Files
- New: `shared/.../graph/ValueLabelEngine.kt`, `shared/src/test/.../ValueLabelEngineTest.kt`
- Modified: `shared/.../graph/GraphLabelPlacementUtils.kt`,
  `app/.../widget/PrecipitationGraphRenderer.kt`, `app/.../widget/CloudCoverGraphRenderer.kt`,
  `desktop/.../PrecipitationGraph.kt`, `desktop/.../CloudCoverGraph.kt`
- Memory: `shared_value_label_engine.md` (+ updates to `desktop_temp_graph_fills_full_width.md`,
  `desktop_precip_graph_android_parity.md`)
- Nothing committed this session — working tree holds exactly the engine changes.

## Possible follow-ups
- Remove `shouldAllowBottomOverflow`/`shouldAllowIconOverlap` + their tests from the cloud renderer
  (now unused by production; engine treats icons as hard obstacles).
- Android emulator visual spot-check of precip/cloud once adb is cooperative.
