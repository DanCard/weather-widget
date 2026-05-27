# Hourly graph: label & order the actual low against a lower forecast low

## Context

On the hourly temperature graph, when the **actual** temperature line bottoms out near a
**forecast** low, two bugs surface depending on the device geometry:

1. **Emulator (Pixel 7 Pro)** — the actual low is **not labeled at all**. Live logs show:
   ```
   ACTUAL_EXTREMA  lowIdx=158 lowTemp=53.17
   LabelSuppressed: role=ACTUAL_LOW idx=158 reason=REDUNDANT
   ```
   The actual low (53.17°) is dropped because it is within **1°** and **≤4 indices** of the
   global/daily low (53.0°, which lives on the forecast curve). That collapse is done by the
   `ACTUAL_LOW` branch of `checkRedundantPairSuppression`.

2. **Samsung (Galaxy Z Fold)** — both lows *are* labeled (there the two values differ by 1.73°,
   above the 1° threshold), but they are stacked **inverted**: the `52°` forecast low sits
   *above* the `53.7°` actual low, even though 52° is the colder/lower point on screen.

The two behaviors come from two independent stages:
- **Suppression** (whether a label appears) — `TemperatureLabelResolver.kt`.
- **Placement direction** (above vs below the point) — `TemperatureGraphRenderer.kt`.

Lows are valleys, so `preferAbove = !placement.isValley` (`TemperatureGraphRenderer.kt:439`)
makes *every* low default to placing its label **below** the point, regardless of neighbors.

**Desired outcome (confirmed with user):**
- Always show the actual low, even when it nearly coincides with the forecast/daily low
  (show both, stacked — accepting that they may round to the same number).
- Order the stacked pair **by value: higher temperature label above, lower below**, so the
  vertical order of the labels matches the vertical order of the points.

Scope: lows only (the user did not ask about the symmetric high case; leave `ACTUAL_HIGH` alone —
it is independently suppressed by `FETCH_DOT` in these scenes).

## Change 1 — Stop suppressing the actual low near the daily low

File: `app/src/main/java/com/weatherwidget/widget/TemperatureLabelResolver.kt`

In `checkRedundantPairSuppression` (`:298`), the `TemperatureRole.ACTUAL_LOW` branch
(`:300`) collapses the actual low into `extrema.dailyLowIndex`. Remove that collapse so the
actual low is always retained.

- `ACTUAL_LOW` only ever resolves at an index **distinct** from `dailyLowIndex` — when the
  global min is itself an actual point, `resolveExtremaRole` (`:183`) returns `LOW` first, so
  there is no separate `ACTUAL_LOW` candidate. Therefore dropping this branch means "always
  show the observed low as its own label," which is exactly the intent.
- Implementation: change the `ACTUAL_LOW ->` arm to `false` (never redundant), or delete the arm
  so it falls through to the `else -> false`. Keep `ACTUAL_HIGH` and all other arms untouched.
- Identical-point dedup is still handled upstream by `deduplicateAnchors` (`:217`), so this does
  not reintroduce true duplicates.

## Change 2 — Order a nearby low pair by value (higher above, lower below)

Files:
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`

The full sorted candidate list is available in `placeTemperatureLabels` (`:413`) before the
per-candidate placement loop. Use it to decide which lows should flip to "above".

1. In `placeTemperatureLabels`, after `sortLabelCandidates`, compute a set of candidate indices
   that should prefer **above** placement: for each low-role candidate
   (`LOW`, `ACTUAL_LOW`, `FORECAST_LOW`, `PAST_FORECAST_LOW`), if there is **another** low-role
   candidate within a small index window whose display value is **strictly lower**, mark this
   (higher) one as "prefer above". The lower one keeps the default (below).
   - Reuse a small window comparable to existing proximity logic (e.g.
     `GraphLabelPlacementUtils.NEARBY_LABEL_WINDOW = 4`); compare rounded values via
     `TemperatureGraphStyle.formatTemp`/`roundToInt` so ties (equal rounded value) do **not**
     force a flip — only a genuinely lower neighbor does.

2. Pass a `forceAbove: Boolean` into `placeSingleLabel` → `drawTemperatureLabel`, and fold it
   into the `preferAbove` decision at `TemperatureGraphRenderer.kt:439`:
   ```kotlin
   val preferAbove = when {
       forceAbove -> true
       valueBasedRoles -> prefersAbovePlacement(candidate)
       else -> !placement.isValley
   }
   ```
   Everything downstream (`directions`, the step/cascade loop, leader lines, the essential-label
   force-place fallback) already honors `preferAbove`, so no other changes are needed there.

Why this works with the existing sort: `sortLabelCandidates` (`:380`) orders valleys by
ascending temperature, so the **lower** low is placed first (below its lower point) and the
**higher** low is placed second with `forceAbove` (above its higher point). The two points are
already separated vertically, so the labels separate cleanly and the value order matches the
visual order.

## Tests

Add focused unit tests (plain JUnit / Robolectric, matching existing style — recall renderer
tests stub `Color`/`Paint` to 0, so assert on placement direction and presence, not colors):

- `TemperatureLabelSuppressionTest.kt` — add a case: actual low within 1° and ≤4 indices of the
  daily low is **retained** (no `REDUNDANT` suppression), asserting an `ACTUAL_LOW` candidate
  survives `collectLabelCandidates`.
- `TemperatureGraphRendererLabelPlacementTest.kt` (or `TemperatureLabelCollisionOrderTest.kt`) —
  construct a series with an actual low above a strictly-lower forecast low nearby; assert via the
  `onLabelPlaced` debug callback that the higher (actual) low is placed `above=true` and the lower
  (forecast/daily) low `above=false`.

Run: `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.TemperatureLabel*" --tests "com.weatherwidget.widget.TemperatureGraph*"`

## End-to-end verification

1. Build & install: `./gradlew installDebug` (installs to all attached devices).
2. Trigger a widget redraw and capture on **both** devices (per CLAUDE.md, convert to JPG before reading):
   - Emulator: `adb -s emulator-5554 exec-out screencap -p > /tmp/e.png && convert /tmp/e.png /tmp/e.jpg`
   - Samsung `RFCT71FR9NT`: screencap to `/sdcard`, `adb pull`, then convert (its `exec-out`
     pipe returned a malformed PNG; the file route works).
3. Confirm on both: the actual low **is** labeled, placed **above** its point, and the
   lower-valued forecast/daily low sits **below** — higher value up, lower value down.
4. Confirm logs: `adb -s <id> logcat -d -s TempLabelResolver:D` shows
   `LabelAccepted: role=ACTUAL_LOW …` (no `LabelSuppressed … ACTUAL_LOW reason=REDUNDANT`), and
   the placement debug reports `above=true` for the actual low.

## Notes / risks

- Showing both lows can render the **same rounded number twice** when they are <1° apart (e.g.
  two `53°`). This is the user's explicit choice ("show both, stacked by value").
- Device divergence is inherent: `actualRedundantWindow = min(4, lastIndex/10)` scales with how
  many hourly points fit, so the old bug only appeared at certain widths. Change 1 removes the
  width-dependent behavior for the actual low entirely.
- Debug logging (`LabelAccepted`/`LabelSuppressed`/`LabelPlacementDebug`) is already in place and
  was the diagnostic source; leave it for a few days of monitoring per existing convention.
