# Fix: hourly temp graph — right-side forecast high not labeled

## Context

On the hourly temperature forecast graph (emulator, NWS), the forecast curve
rises to a **68° crest** on the right side then eases down to a **66° endpoint**,
but only the 66° endpoint label is drawn — the genuine 68° crest is unlabeled.
Confirmed from device logcat + screenshot (forecast tail `…66, 67, 68, 68, 67, 66`,
crest at idx 164 of 167).

### Root cause

`TemperatureLabelResolver.checkEndpointSuppression` (shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt:687-713)
is an edge-proximity declutter rule that drops *secondary forecast extrema*
(`FORECAST_HIGH/FORECAST_LOW/PAST_FORECAST_HIGH/PAST_FORECAST_LOW`) whenever they
fall within `edgeWindow` indices of an edge. For this render:
`forecastHighIndex=164`, `lastIndex=167` → `edgeWindow = min(5, 167/15) = 5`,
`edgeDist = min(164, 167-164) = 3`; since `3 <= 5` and idx != endpoint, the crest is
suppressed before it ever reaches the placement engine.

The rule's **priority is backwards**: it keeps the positional START/END *endpoint*
label and throws away the more meaningful *extremum*. The drop is also **silent** —
the suppression-logging guards (lines 258, 267, 276, 284, 292, 303) only fire for
`HIGH/LOW/ACTUAL_*`, never for `FORECAST_*`, so nothing appears in logcat.

### Intended outcome (user directive)

> "The endpoint-declutter rule should prioritize temperature extrema rather than
> endpoints."

A near-edge forecast extremum should always be labeled. When an endpoint label
merely echoes that extremum's value, drop the **endpoint**, not the extremum. This
priority already exists on the endpoint side: `checkRedundantPairSuppression`'s
`START/END` branch (lines 600-627) drops START/END when value-redundant
(`< 2°`, pixel-near) with a nearby forecast extremum (`secondaryForecastTargets`).

## Approach

Remove the extrema-suppressing endpoint rule and rely on the existing
endpoint-redundancy logic for decluttering.

### Edits — `TemperatureLabelResolver.kt`

1. **Delete `checkEndpointSuppression`** (the function, lines 687-713) and its call
   block in `collectLabelCandidates` (lines 291-297). After deletion, near-edge
   forecast extrema survive; an endpoint that duplicates one is dropped by the
   already-present `checkRedundantPairSuppression` START/END branch.

2. **Un-silence `FORECAST_*` logging.** Introduce a single role set and use it for
   every suppression/accept log guard so future drops are visible in logcat
   (logcat-only `Log.v`, never persisted — matches the project's permanent
   graph-label trace-logging preference):

   ```kotlin
   private val LOGGED_SUPPRESSION_ROLES = setOf(
       TemperatureRole.ACTUAL_HIGH, TemperatureRole.HIGH,
       TemperatureRole.ACTUAL_LOW,  TemperatureRole.LOW,
       TemperatureRole.ACTUAL_END,  TemperatureRole.END,
       TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW,
       TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW,
   )
   ```

   Replace each identical guard
   `if (role == TemperatureRole.ACTUAL_HIGH || … || role == TemperatureRole.END)`
   at the remaining sites (LEFT_EDGE 258, FETCH_DOT 267, REDUNDANT 276,
   TRANSITION 284, LabelAccepted 303) with `if (role in LOGGED_SUPPRESSION_ROLES)`.

### Why this is safe

- The crest is not caught by any other gate: transition-boundary suppression uses
  `window = min(3, 167/20) = 3`, `abs(164-154)=10 > 3` (no); redundancy for
  `FORECAST_HIGH` only compares against `actualHighIndex` (far away); dense-filter
  keeps it (it is an explicit/immovable anchor — `forecastHighIndex`).
- The only existing endpoint test (`absolute actual low just inside the right edge
  is not endpoint-suppressed`, TemperatureLabelSuppressionTest.kt:562) exercises
  `ACTUAL_LOW`, which was already exempt — it still passes after deletion.
- Shared module, so both Android widget and desktop app benefit (single code path).

## Tests — `shared/src/test/kotlin/com/weatherwidget/shared/graph/TemperatureLabelSuppressionTest.kt`

Add a fixture helper producing a forecast that crests just inside the right edge
(global daily HIGH lives earlier so the crest's role is `FORECAST_HIGH`, not `HIGH`):

```kotlin
// n=60, forecast region 40..59; global daily HIGH (78) at idx 8 keeps the crest
// FORECAST_HIGH. Crest at idx 56 (edgeDist=3, inside the old edgeWindow), eases to END@59.
private fun rightEdgeCrestHours(crest: Float, endValue: Float): List<HourData> {
    val start = LocalDateTime.of(2026, 6, 13, 0, 0); val n = 60; val effEnd = 40
    return (0 until n).map { i ->
        val dt = start.plusHours(i.toLong())
        val f = when {
            i <= effEnd -> 78f - 0.5f * kotlin.math.abs(i - 8)
            i < 56      -> 62f + (crest - 62f) * (i - effEnd) / (56 - effEnd).toFloat()
            i == 56     -> crest
            else        -> crest + (endValue - crest) * (i - 56) / (59 - 56).toFloat()
        }
        HourData(dt, f, "${dt.hour}h", isActual = i <= effEnd, actualTemperature = f - 13f)
    }
}
```

- **Test A — crest distinct from END is kept** (the live case): `crest=68f, endValue=66f`;
  assert a candidate exists with `index == 56 && role == FORECAST_HIGH`.
- **Test B — extrema win over endpoints** (echo case): `crest=67.8f, endValue=67.5f`
  (0.3° apart, pixel-near); assert the crest (`56, FORECAST_HIGH`) is kept **and**
  the endpoint (`59, END`) is now suppressed as redundant.

Use `computeExtremaIndices(hours, 400f, 40, null)` + `collectLabelCandidates(... effectiveActualEndIndex = 40, transitionX = 400f, observedAt = null, widthPx = 567)`,
mirroring the existing endpoint test at line 587. If extrema indices land off the
expected positions, print `candidates.map { it.role to it.index }` and nudge control
values (same debugging approach as the line-562 fixture).

## Verification

1. Unit tests:
   `./gradlew :shared:testDebugUnitTest --tests "*TemperatureLabelSuppressionTest*"`
   (new Test A/B pass; existing endpoint + redundancy tests still pass).
2. Build + install: `./gradlew installDebug`.
3. On the emulator, open the hourly temperature graph for a day whose forecast
   crests near the right edge before easing down (the captured NWS case). Confirm the
   crest now shows its `68°` label.
4. Pull logcat and confirm the decision is now traceable:
   `adb -s emulator-5554 logcat -d | grep -E "TempLabelResolver|FORECAST_HIGH"`
   — a `LabelAccepted … role=FORECAST_HIGH` line should appear (previously absent).
5. Screenshot to JPG for reading (per CLAUDE.md):
   `adb -s emulator-5554 exec-out screencap -p > /tmp/s.png && convert /tmp/s.png /tmp/s.jpg`.

## Files
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt` (fix + logging)
- `shared/src/test/kotlin/com/weatherwidget/shared/graph/TemperatureLabelSuppressionTest.kt` (tests)
