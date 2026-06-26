# Drop the left-edge forecast START label when it duplicates a pixel-near forecast HIGH/LOW

## Context

In the **24-hour temperature view** (emulator), the left edge stacks three labels: `75°`
(forecast daily HIGH), `74°` (actual high, pink), and `73°` (forecast **START** boundary). The
`73°` START is redundant noise — it sits ~2° below the forecast HIGH on the **same** (forecast)
line and only a few pixels away, so it adds nothing the `75°` already conveys. The user wants the
`73°` dropped. (The right-side `73.1°` is the fetch-dot current-temp label — a separate,
intentionally-drawn element — and is explicitly out of scope.)

### Why the existing redundancy logic misses it

`checkRedundantPairSuppression` (shared `TemperatureLabelResolver.kt`, START/END branch, ~L594-612)
already suppresses a boundary label when a stronger label is BOTH pixel-near and value-near. From
the live logs the pair IS pixel-near — `START idx=0` (displayed `73`) and `HIGH idx=28 val=75.0` are
~2h apart in a 24h span ≈ 49px, inside the 64px `REDUNDANT_PAIR_PX` budget. The only thing keeping
START alive is the **value gate**: same-series forecast targets are compared with the strict raw
`abs(diff) < 2f`, and 73-vs-75 is ~2.0 — it just misses. (The earlier 3-day fix suppressed START via
the *actual* per-day high 0.3° away; here the redundant neighbor is a *forecast* extreme exactly 2°
away.)

## Approach

Make the **same-series (forecast) boundary redundancy** match how the user reads the graph — "73 is
within 2 of 75" — by comparing the **displayed/rounded** values with a `≤ 2°` tolerance. Leave the
**cross-series (actual) comparison untouched** at the strict raw `< 2f`, so the forecast-vs-actual
pairings we deliberately keep side-by-side (and the existing tests for them) are unaffected.

### Change (one file: `shared/.../graph/TemperatureLabelResolver.kt`)

1. Add a constant near the other redundancy constants (~L29-38):
   ```kotlin
   // A boundary START/END label is redundant with a pixel-near SAME-SERIES forecast extreme when
   // their DISPLAYED values are within this many degrees — matches how the user reads "73 ≈ 75".
   // Looser than the strict cross-series (actual) gate, which stays < 2f so forecast-vs-actual pairs
   // (different series the user compares) keep both labels.
   private const val SAME_SERIES_BOUNDARY_REDUNDANT_DEGREES = 2
   ```
2. In the `START, END ->` branch, change ONLY the `forecastTargets.any { ... }` value test (L606-608)
   from the raw `abs(labelTemps[idx] - labelTemps[tIdx]) < redundantValueThreshold` to a rounded
   comparison:
   ```kotlin
   abs(labelTemps[idx].roundToInt() - labelTemps[tIdx].roundToInt()) <= SAME_SERIES_BOUNDARY_REDUNDANT_DEGREES
   ```
   The `actualTargets.any { ... }` test (L609-611) is left as-is (raw `< redundantValueThreshold`).
   `roundToInt` is already imported/used in this file.

Result: START(73) is redundant against the pixel-near forecast HIGH(75) — rounded diff 2 ≤ 2 — and is
suppressed, leaving the informative `75°` (forecast) and `74°` (actual). Applies symmetrically to END
(right-edge forecast endpoint) for consistency; in the observed view END(68) is >2° from any
pixel-near forecast extreme so it is unaffected.

### Why existing tests still pass (cross-series gate unchanged)

- `TemperatureLeftEdgeStartOrderTest` — START 64 paired with actual 66.9 (cross-series, 2.9°): the
  actual gate is unchanged, and the nearest forecast extreme (HIGH 90) is pixel-far → not suppressed.
- 3-day `TemperatureLabelSuppressionTest` regressions — main case suppresses via the *actual* per-day
  high (unchanged path); control START 71 vs forecast HIGH 75 is a rounded diff of **4 > 2**, so the
  new forecast gate does not touch it → still retained.

## Tests

Add to `shared/src/test/.../TemperatureLabelSuppressionTest.kt` a 24h-style hourly case (≈25 points,
`widthPx=584`) where the forecast starts ~2° below a pixel-near forecast HIGH, with the actual line
placed well below START so only the forecast path is exercised:
- forecast `START` rounds to 2° under a HIGH a couple of hours in → **START suppressed**.
- control: forecast `START` 3° under the HIGH → **START retained** (proves the gate is `≤ 2`, not open-ended).

## Verification

1. `./gradlew :shared:test --tests "com.weatherwidget.shared.graph.*"` — new case passes; existing
   left-edge/pairing/suppression tests stay green.
2. `ANDROID_SERIAL=emulator-5554 ./gradlew installDebug`, force a cold start
   (`adb -s emulator-5554 shell am force-stop com.weatherwidget` then broadcast
   `com.weatherwidget.ACTION_REFRESH -n com.weatherwidget/.widget.WeatherWidgetProvider -f 0x400020`),
   screenshot the 24h view (`screencap -p | convert … jpg`) and confirm the left edge now shows
   `75°` + `74°` only — no `73°`.
3. Confirm via logs the START drop is for the right reason and other labels are intact:
   `adb -s emulator-5554 logcat -d | grep -E 'LabelSuppressed.*role=START.*REDUNDANT'` and that the
   forecast HIGH/LOW and END are unchanged.
