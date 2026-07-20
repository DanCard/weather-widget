# Blend window-independence audit

Follow-up to [`260719-fetch-dot-temperature-derivation.md`](260719-fetch-dot-temperature-derivation.md),
which flagged that the fetch dot (12h/3h context) and the hourly graph (72h/60h) run the *same*
blender over *different* windows, and that device logs appeared to show them disagreeing (61.6 vs
61.1 at t=20:50).

**Verdict: the dot is fine. My window hypothesis was wrong.** But the audit did turn up a real,
undocumented precondition on `blendObservationSeries`.

Reproduced with `BlendWindowIndependenceTest` + `DeviceBlendFixture` (1672 real NWS observations and
519 hourly rows captured from SM-F936U1, driven through the actual shared code — no mocks).

## Finding 1 — the dot is window-independent (hypothesis disproved)

Swept the context width from 2h to 72h. The dot's own timestamp is **61.58° at every single width**:

```
lookback  points   t=20:50    vsRef   maxDelta  maxDeltaAt      minAfterWindowStart
2h            24     61.58    +0.00       1.83  07-19 19:00      0 min
3h            38     61.58    +0.00       4.90  07-19 18:00      0 min
6h            79     61.58    +0.00       5.54  07-19 15:00      0 min
12h          163     61.58    +0.00       1.83  07-19 09:02      2 min
24h          322     61.58    +0.00       1.96  07-18 21:00      0 min
72h          977     61.58    +0.00       0.00  07-16 21:00      0 min
```

The dot and the graph agree exactly (`61.580162` both). The 61.6-vs-61.1 in the logs has some other
cause — most likely those `TEMP_ACTUALS_DEBUG emit` lines are throttled samples from different
renders or different widgets (the message carries **no widget id**, see "Diagnostic gap" below).
Not the windows.

## Finding 2 — `startMs`/`endMs` never affect the math

Easy to misread. In `blendObservationSeries`, the window arguments appear only at the very end:

```kotlin
if (targetTs in startMs..endMs) { result.add(...) }
```

`filtered`, `candidateTimes` and `byStation` are all built from the **entire** `observations` list.
So the window gates *emission only*. The documented invariant ("makes the emitted series independent
of the query window") is true and now pinned by
`emission window does not change any blended value`.

## Finding 3 — the real fragility is the caller's QUERY, not the window

What actually moves numbers is which observation rows the caller **queried**. `resolveStationValueAt`
needs a reading at or before the target to resolve a station there:

- exact hit → `observed`
- `before` + `after` within 3h → `interpolated`
- `before` only, within 3h → `forecast_extrapolated`
- **no `before` at all → `null`, station drops out and the IDW renormalises over the survivors**

At the leading edge of a narrowly-queried range, earlier readings simply aren't in the list, so
stations silently vanish from the blend. Measured divergence vs the wide-context answer:

| region of a 12h query | worst error |
|---|---|
| first 60 min | **up to 5.54°F** (6h query, at its start) |
| beyond 60 min | **0.00°F** — exact match |

`maxDeltaAt` lands **0–10 minutes after the window start** at every width tested. It is purely a
leading-edge artifact, and it decays fast (1.83 → 1.60 → 0.34 → 0.08 across the first 45 min of the
12h window).

## Why nothing is broken today

Every current caller of `blendObservationSeries` is accidentally safe:

| caller | why it's safe |
|---|---|
| hourly graph (`ActualTemperatureSeriesBuilder.build`) | queries 72h; visible zoom window sits deep in the interior |
| `ActualsAggregator.aggregate` | pads each day by `DAILY_BLEND_CONTEXT_MS` = **±24h** |
| `YesterdayDeltaCalculator.computeDelta` | reads at the centre of a ±90 min *emission* window, but the Android caller hands it the **full 72h** observation list |
| fetch dot (`resolveCurrentObservation`) | queries 12h but only ever reads the **latest** point — the trailing edge |
| `ObservationRepository` `EXTREMA_WINDOW_DIAG` | diagnostic probe only |

That safety is a property of each call site, not of the function. Nothing states the precondition,
and nothing stops the next caller from querying a tight range and reading its start —
`YesterdayDeltaCalculator` in particular would land squarely in the bad zone if a future caller ever
passed it a narrowly-queried list instead of the 72h one.

## Suggested follow-ups (none applied)

1. **Document the precondition** on `blendObservationSeries`: *callers must query at least
   `MAX_INTERPOLATION_GAP_MS` (3h) of observations before the earliest timestamp they intend to
   read.* Cheapest fix; makes the existing accidental safety deliberate.
2. **Consider making it structural** — e.g. have the function take a `readableFrom` and refuse to
   emit before `min(observation timestamps) + 3h`, so an under-queried caller gets no points rather
   than plausible-but-wrong ones.
3. ~~**Diagnostic gap:** `TEMP_ACTUALS_DEBUG` `emit` lines carry no widget id and no context-window
   marker, so two renders' samples are indistinguishable in `app_logs`.~~ **Done** —
   `TemperatureStateResolver` now stamps every persisted line with
   `widget=… source=… aligned=… zoom=…`. Note this changes the message shape, so queries must not
   assume the message starts with `emit`/`summary`:

   ```sql
   -- before: message LIKE 'emit t=20:%'
   SELECT datetime(timestamp/1000,'unixepoch','localtime'), message FROM app_logs
    WHERE tag='TEMP_ACTUALS_DEBUG' AND message LIKE '%emit t=20:%';
   ```

## Test assets left in the repo

- `shared/src/test/kotlin/com/weatherwidget/shared/actuals/BlendWindowIndependenceTest.kt` — 3 tests,
  all passing. The third asserts the edge artifact *is present*, with a message telling whoever
  fixes it to convert the test to a strict equality check.
- `shared/src/test/kotlin/com/weatherwidget/shared/actuals/DeviceBlendFixture.kt` +
  `shared/src/test/resources/device-blend/*.tsv` — real captured rows.

Two gotchas worth remembering for future fixtures of this size:

- **1672 inlined constructor calls blow the JVM's 64 KB `<clinit>` limit** — `Method too large`.
  Load from a test resource instead.
- **TSV, not CSV.** NWS station names contain commas ("San Jose, San Jose International Airport").

Run with:

```bash
./gradlew :shared:test --tests "com.weatherwidget.shared.actuals.BlendWindowIndependenceTest"
```
