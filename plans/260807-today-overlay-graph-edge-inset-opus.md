# Today-column overlay: reclaim the redundant graph-edge inset so all rows fit ABOVE

## Context

On the emulator (Tomorrow.io as the displayed source), the large daily view splits the Today-column
overlay across two zones: `+0.0 fcst` renders above the column, while `58.7°` / `0m` render **on top
of the forecast bars**. Visually there is clearly room above the column for all three rows.

There is room — by 1.9 px too little. Captured from the live render
(`adb logcat -s TodayColumnOverlay:V`, 06:07:39, the render in the screenshot):

| quantity | value |
|---|---|
| `graphTop` | 51.197 |
| `padding` (`VERTICAL_PADDING_DP=3` x density 2.625) | 7.875 |
| ABOVE band start (`graphTop + padding`) | 59.072 |
| today's high label `74.3°` (hard obstacle) | y 138.232 .. 170.495 |
| **largest free run in ABOVE** | **79.160** |
| stack: delta 26.147 + rowSpacing 1.3125 + temp/age 53.607 | **81.067** |
| **deficit** | **1.907** |

So `fitGroupInZone(lines, ABOVE, ...)` fails, and the planner correctly falls through its cost ladder
to rank 3 — the clean `ABOVE` + `ON_COLUMN` split introduced by `e688223e`. The planner is behaving
as designed; the **input geometry** is wrong.

`Input.padding` conflates two unrelated roles:

1. an inset from the graph's **outer edge** (`graphTop` / `graphBottom`), and
2. **clearance from the bar cap** (`barTop` / `barBottom`).

Role 1 is redundant. `DailyGraphLayoutResolver.kt:184` already sets
`graphTop = topPadding = TOP_PADDING_DP(39dp) * labelScale * density` — the entire header band
(current temp, date, API indicator) is *already* excluded. Insetting a further 7.875 px below it buys
nothing and is precisely what costs the fit. Desktop is the same: `DailyForecastGraph.kt:670` passes
`graphTop = top`, the graph area top.

Note role 2 is moot in this geometry anyway: today's own high label caps the run at 138.232, well
above `barTop - padding` = 153.78.

Intended outcome: when the free space above the Today column can hold the whole stack, the whole
stack renders above the column. When it genuinely cannot (e.g. a hot day pushes `barTop` to 68 px, as
seen at 06:06:52 in the same log), the existing split / on-column fallbacks are unchanged.

## Change

### 1. `shared/src/main/kotlin/com/weatherwidget/shared/graph/TodayColumnOverlayPlanner.kt`

Split the two roles. Add a **final** constructor parameter to `Input` (last position, so the
positional call sites in `TodayColumnOverlayPlannerTest.kt:34` and friends keep their meaning):

```kotlin
/**
 * Inset from the graph's OUTER edge (graphTop / graphBottom), as distinct from [padding], which
 * is clearance from the bar cap. Defaults to [padding] for callers that do not distinguish them.
 * Both renderers pass 0: graphTop/graphBottom are already computed margins (Android's is
 * TOP_PADDING_DP=39dp of header band), so a second inset there is pure lost headroom.
 */
val edgeInset: Float = padding,
```

Use it in `bandFor` (the only reader of `padding`):

```kotlin
Zone.ABOVE  -> (input.graphTop + input.edgeInset) to (input.barTop - input.padding)
Zone.BELOW  -> (input.barBottom + input.padding) to (input.graphBottom - input.edgeInset)
Zone.ON_COLUMN -> unchanged
```

Extend the KDoc's zone description to record why the edge inset is zero.

### 2. Both call sites pass `edgeInset = 0f`

- `app/src/main/java/com/weatherwidget/widget/TodayColumnOverlayRenderer.kt:113-127`
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt:797-810`

Result for the captured geometry: run becomes `51.197..138.232` = **87.04 px** vs an 81.07 px stack —
5.97 px slack, and the stack centres itself in the run via the existing clearance tie-break (top
lands at ~54.2, i.e. still 3 px clear of the panel edge).

Hysteresis cannot pin it back: the previous zones were both `ON_COLUMN` (`onBars = true`), which is
not `sameStrengthAs` the new `onBars = false` winner, so `takeWhile` stops before reaching it.

### Deliberately NOT changed

- `padding` (bar clearance) keeps its 3dp value.
- `padding` is still not multiplied by `labelScale` even though `rowSpacing` beside it is
  (`TodayColumnOverlayRenderer.kt:69`). That is a real inconsistency, but it is not what is breaking
  this layout, and widening the fix risks other geometries.
- No font shrinking — removed at user request; degradation stays row-dropping only.

## Verification

**1. Unit test (new) — must fail before, pass after.**
Add to `shared/src/test/kotlin/com/weatherwidget/shared/graph/TodayColumnOverlayPlannerLayoutTest.kt`
a sibling of the existing `emulatorInput` block, using the numbers actually captured today
(the existing fixture uses an older, roomier geometry that already passes):

```kotlin
// Captured 2026-08-07 06:07:39 from emulator-5554. The ABOVE free run is 79.16 px against an
// 81.07 px stack — short by 1.91 px purely because of the redundant graphTop inset.
graphTop = 51.196808f, graphBottom = 359.5991f,
barTop = 161.65521f, barBottom = 290.52335f,
columnLeft = 126.27027f, columnRight = 205.1892f,
hardObstacles = listOf(Bounds(134.72974f, 138.23172f, 196.72974f, 170.49461f)),
padding = 7.875f, edgeInset = 0f, rowSpacing = 1.3125f,
lines = Line("delta", "+0.0 fcst", 68.12375f, 26.14746f),
        Line("dominant_temp_age", "58.7°\n0m", 50.0f, 53.60742f)
```
Assert both blocks land in `Zone.ABOVE` and do not intersect. Also assert the same input with
`edgeInset = padding` (the old behaviour) still yields the split, so the test proves the inset is the
cause rather than just asserting the happy path.

**2. Existing suites**
```bash
./gradlew :shared:testDebugUnitTest --tests "*TodayColumnOverlay*"
./gradlew :app:testDebugUnitTest --tests "*DailyLargeTodayLayoutRoboTest" --tests "*TodayOverlaySettingsRoboTest" --tests "*TodayColumnOverlayPlannerTest"
```
`DailyLargeTodayLayoutRoboTest` is the one to watch — per the previous rewrite it was passing
*because of* a workaround, so it is the sensitive canary here.

**3. Android on the emulator (the reported case)**
```bash
./gradlew installDebug
adb -s emulator-5554 logcat -c && adb -s emulator-5554 logcat -d -s TodayColumnOverlay:V
adb -s emulator-5554 exec-out screencap -p > /tmp/s.png && convert /tmp/s.png /tmp/s.jpg
```
Expect `placements=[delta:ABOVE, dominant_temp_age:ABOVE]` and all three rows above the column in the
screenshot. Also step the day-nav arrows to re-render at a few different `barTop` values and confirm
nothing regresses.

**4. Desktop parity**
```bash
scripts/buildStart-desktop.sh
```
Confirm `todayOverlay layout ... placements=[delta:ABOVE, dominant_temp_age:ABOVE]` in the desktop log
and that the overlay is not drawn across the bars.
