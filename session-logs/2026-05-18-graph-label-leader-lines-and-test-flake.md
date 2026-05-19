# 2026-05-18 — Graph Label Leader Lines, Value-Based Placement, and a Test-Flake Root Cause

## Summary

Two interleaved threads:

1. **Graph label crowding (UX work in `TemperatureGraphRenderer.kt`).** User reported that on a steep rising slope, the red `81.4°` actual label was visually crammed into the curve. Iteratively added (a) curve-vs-label geometric collision detection, (b) exact-fit displacement so leader lines stay short, and (c) value-based direction preference so higher-value labels appear higher on screen (peaks above, lower-value mid-slope points below). User accepted the current Samsung-device result ("not too bad, can live with it").
2. **Test-flake root cause investigation.** While running `./gradlew testDebugUnitTest`, `WidgetStateManagerTest > get current temp delta state migrates matching legacy widget scoped state` failed with a bare `java.lang.AssertionError`. Test passes in isolation. Traced the root cause to `WeatherDatabase.isTesting` being a global static flag that's flipped on (but not reset) by `HistoryClampingRegressionRoboTest`, combined with `SharedPreferencesUtil.getPrefsName()` silently rewriting prefs names when that flag is set. The failing test bypassed the util for one of its two prefs handles, so the two diverge under test-mode.

## Part 1 — Label rendering

### Reported visual problem

Pre-existing screenshot (Pixel 7 emulator, widget at top-left of home screen):
- The red `81.4°` label (role: `ACTUAL_END` / `ACTUAL_HIGH`, last observed before NOW) sat at the rightmost end of the rising actual curve, placed at the standard ~2dp "above" gap.
- Because the curve climbed steeply through the label's horizontal extent, the slope visually intruded into the label rect on its left side.
- The existing collision detection only checked **label-vs-label** (`drawnLabelMetas`) and **label-vs-icon** (`drawnIconBounds`) — there was no concept of **label-vs-curve** overlap.

User asked: "What about using a down leader line for less overlap?"

### Architectural finding (insight worth keeping)

The renderer already had complete leader-line *infrastructure*:
- `placement.leaderLinePaint` exists per role (`actualLeaderLinePaint`, `forecastLeaderLinePaint` in `TemperatureGraphStyle.kt`).
- `placeTemperatureLabels` already calls `ctx.canvas.drawLine(...)` whenever `step > 0` displaces a label past its natural position (line ~401 of the renderer).
- The "step loop" `for (step in 0..MAX_LEADER_DISPLACEMENT_STEPS)` only escalates when `hasCollision` is true.

So adding leader lines wasn't new code — it was a new **trigger condition**: detect that the curve overlaps the label rect and feed that into `hasCollision`.

### Iteration 1 — Boolean curve-overlap check

Added `curveIntrudesIntoLabel(points, bounds): Boolean` to `TemperatureGraphRenderer.kt`. Walks adjacent point pairs in `actualVisiblePoints` and `forecastPoints`, clips each segment to the label's horizontal extent, and tests whether the segment's Y interval overlaps the label's Y interval. Scoped via `CURVE_AVOIDANCE_ROLES = { ACTUAL_END, ACTUAL_HIGH, ACTUAL_LOW, HIGH, LOW, LOCAL }`.

Result on emulator: the `81.4°` was displaced upward into the icon row level with a leader running back down — but the leader was **too long** because the existing step-loop displacement increment is one full `labelHeight` per step.

### Iteration 2 — Exact-fit displacement (shorten the leader)

Rewrote the helper to return a `CurveIntrusion(minY, maxY)` data class. The min/max are the curve's bounding Y values inside the label rect. Added a `tryExactFitCurveAvoidance(...)` pre-check that runs *before* the standard step loop:

- Compute the candidate's base bounds at the natural gap.
- If the curve intrudes, compute the *minimum* extra displacement needed to clear it (`baseBounds.bottom - intrusion.minY + CURVE_AVOIDANCE_CLEAR_PX` for above; `intrusion.maxY + CLEAR - baseBounds.top` for below).
- Build the new bounds at that exact offset. Verify on-screen, no label/icon overlap, no residual curve intrusion.
- If clean, draw with the existing `leaderLinePaint`.

This decoupled leader length from `labelHeight` granularity. Visually much tighter.

### Iteration 3 — "Higher value goes higher up" (user-stated rule)

User feedback: "I liked it better when the 81.4 label was below the graph line and the 83 forecast was above the graph line. Maybe some prioritization technique like, higher value label goes higher up?"

Captured this as **`ACTUAL_END`/`LOCAL` should defer the above-curve slot to a higher-value peer.** Implementation:

```kotlin
private fun prefersAbovePlacement(candidate: TempLabelCandidate): Boolean {
    // window: ±VALUE_NEIGHBOR_WINDOW indices in labelTemps (spans actuals + forecast)
    // nearMax = max temp in window
    // prefer above iff (nearMax - v) < SIGNIFICANT_MAX_GAP (i.e. you ARE the local max)
}

val valueBasedRoles = candidate.role == ACTUAL_END || candidate.role == LOCAL
val preferAbove = if (valueBasedRoles) prefersAbovePlacement(candidate) else !placement.isValley
val directions = if (preferAbove) listOf(true, false) else listOf(false, true)
```

`labelTemps` already spans both actual and forecast hours, so for `ACTUAL_END` on a rising slope the upcoming forecast peak shows up in the window. `SIGNIFICANT_MAX_GAP = 1.0f` means "within 1°F of the local max → you're a peer of the peak, go above; else defer".

### Bug fix — pre-check was probing both directions

Logs revealed every curve-eligible label was being placed in the **wrong** direction:
```
LabelPlacementDebug(role=HIGH,    temperature=83.0,     placedAbove=false, reason=below+curveFit(24.5px))
LabelPlacementDebug(role=LOW,     temperature=54.0,     placedAbove=true,  reason=above+curveFit(9.1px))
LabelPlacementDebug(role=ACTUAL_LOW, temperature=63.95, placedAbove=true,  reason=above+curveFit(14.0px))
```

Cause: `tryExactFitCurveAvoidance` looped over **both** directions. When the preferred direction had no curve intrusion, the loop `continue`d to the secondary direction, which *did* have intrusion (because going against the natural side puts the curve into the label rect), and placed it there with a long leader.

Fix: only check the **preferred** direction in the pre-check. If no intrusion → `return false` and let the main step loop handle the natural placement. If intrusion → exact-fit; if that fails any constraint → `return false` and let the step loop fall back. The main step loop's curve check still acts as a safety net for the "both directions blocked" case.

### Files touched

- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` — only file in `git status`. Added:
  - `CURVE_AVOIDANCE_ROLES`, `CURVE_AVOIDANCE_MARGIN_PX`, `CURVE_AVOIDANCE_CLEAR_PX`
  - `CurveIntrusion` data class with `merge()` helper
  - `curveIntrusionInLabel(points, bounds)` and `combinedCurveIntrusion(ctx, bounds)`
  - `VALUE_NEIGHBOR_WINDOW`, `SIGNIFICANT_MAX_GAP`, `prefersAbovePlacement()`
  - `tryExactFitCurveAvoidance()` (preferred-direction-only after bugfix)
  - `overlapsCurve` factored into `hasCollision` in the main step loop
  - `curve-collision(...)` rejection-reason log path
  - Expanded `LabelPlacementDebug` log filter to include `ACTUAL_END` and `LOCAL` roles

### Final state on Samsung device

User: "Not too bad, if it is not very easy to fix, can live with it." Stopping here. Current Samsung shows `81.8°` with a slightly long leader (~30px). Pixel emulator shows the user-preferred layout with `83°` above and the lower-value actual below.

## Part 2 — Flaky-test investigation

### Symptom

`./gradlew testDebugUnitTest` failed with one failure:
```
WidgetStateManagerTest > get current temp delta state migrates matching legacy widget scoped state
java.lang.AssertionError    (no detail message)
```

Running the test in isolation:
```
./gradlew testMediumDebugUnitTestFresh \
  --tests "com.weatherwidget.widget.WidgetStateManagerTest.get current temp delta state migrates matching legacy widget scoped state"
→ PASSED
```

Classic order-dependent flake.

### The failing test

`app/src/test/java/com/weatherwidget/widget/WidgetStateManagerTest.kt:387-413`. Setup:
```kotlin
val migrationPrefs = context.getSharedPreferences("delta_migration_prefs", Context.MODE_PRIVATE)
migrationPrefs.edit().clear().apply()
WidgetStateManager.setPrefsNameOverrideForTesting("delta_migration_prefs")
val migrationManager = WidgetStateManager(context)
migrationPrefs.edit().putFloat("widget_current_temp_delta_$w", -4f) /* + 6 other legacy keys */ .apply()

val migrated = migrationManager.getCurrentTempDeltaState(w, WeatherSource.NWS)
assertNotNull(migrated)   // <-- fails when isTestingMode is on
```

### Root cause

`app/src/main/java/com/weatherwidget/util/SharedPreferencesUtil.kt`:
```kotlin
fun getPrefsName(name: String): String {
    if (WeatherDatabase.isTestingMode() && !name.contains("_test")) {
        return "${name}_test_default"
    }
    return name
}
```

`WidgetStateManager.prefs` is lazy-initialized via `SharedPreferencesUtil.getPrefs(context, prefsNameOverride ?: PREFS_NAME)` (line 116-118). So:

| `isTestingMode()` | Test writes to | Manager reads from |
|---|---|---|
| `false` | `delta_migration_prefs` | `delta_migration_prefs` ✅ |
| `true`  | `delta_migration_prefs` | `delta_migration_prefs_test_default` ❌ |

The test uses `context.getSharedPreferences("delta_migration_prefs", ...)` *directly* (bypassing the util) for its `migrationPrefs` handle. When `isTestingMode` is true, the two handles point to different SharedPreferences. The manager's read finds nothing, returns null, `assertNotNull` fails.

The other tests in `WidgetStateManagerTest` don't trip this because they do reads and writes both through the manager's API, so the suffix application is symmetric.

### Who flips the flag without resetting?

`WeatherDatabase.isTesting` is a static field on the companion. Tests that flip it via `setIsTesting(true)`:

| Test | Sets to true | Resets in @After? |
|---|---|---|
| `WeatherObservationsActivityRobolectricTest` | yes (@Before) | **yes** (@After resets to false) ✅ |
| `HistoryClampingRegressionRoboTest` | yes (@Before) | **no** ❌ |

`HistoryClampingRegressionRoboTest` is the offender. Once it runs in a suite, the global flag stays true for every subsequent test in the JVM. (Also relevant: `ScreenOnReceiverTest` uses `setDatabaseForTesting` — a different setter that also flips the flag — worth checking separately.)

### Possible fixes (not applied this session)

A. **Local defensive fix** — make `WidgetStateManagerTest`'s `@Before` call `WeatherDatabase.setIsTesting(false)` so it starts from a known state.

B. **Per-offender fix** — add `@After { WeatherDatabase.setIsTesting(false) }` to `HistoryClampingRegressionRoboTest` (and review any other suspects).

C. **Test-side hygiene** — change the failing test to use `SharedPreferencesUtil.getPrefs(context, "delta_migration_prefs")` for its `migrationPrefs` handle, matching what the manager does. Removes the asymmetry regardless of flag state.

D. **API surface** — make `WeatherDatabase.setIsTesting` non-static or auto-reset via a JUnit Rule. Larger blast radius.

Recommendation: **C** is the minimal, target-specific fix (one line in one test, no cross-test coupling). **B** is the right hygiene fix if you want every test to be a good citizen. Both can coexist.

## What didn't get done

- Did not deploy the latest source (with the pre-check bugfix and the value-based-direction preference) to any device. The Samsung screenshot the user inspected was from an earlier installed build with the buggy pre-check still active; user explicitly chose to live with it rather than iterate further visually.
- Did not run `./scripts/emulator-tests.sh` (instrumented tests). User chose to investigate the unit-test flake instead.
- Did not apply any of the four flake fixes — investigation only.

## Memory notes worth saving (separate task)

- The `_test_default` prefs suffix added by `SharedPreferencesUtil.getPrefsName()` when `isTestingMode()` is on is a stealth source of Robolectric flakes. Any test that reads SharedPreferences *both* through `SharedPreferencesUtil` and *directly* via `context.getSharedPreferences(...)` will diverge if the flag is on. Prefer to route everything through the util in tests.
- `MEMORY.md` currently records the test script as `./scripts/run-emulator-tests.sh` — the real filename is `./scripts/emulator-tests.sh` (CLAUDE.md is correct). Update memory.
