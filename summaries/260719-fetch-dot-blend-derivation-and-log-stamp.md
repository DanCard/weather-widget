# Fetch-dot 61.6° derivation, blend window audit, and TEMP_ACTUALS_DEBUG render stamp

## Question (2026-07-19, Samsung SM-F936U1 / RFCT71FR9NT)

"How is the 61.6 temp for the fetch-now dot calculated?" — widgets 345/349, source NWS, 21:09 local.
Not a bug report; the answer turned into an audit and one production change.

## Finding 1 — the dot is a blend, not a reading

No station read 61.6°. Every raw NWS observation near the dot's timestamp was 62.6–66.2°, yet the
dot sat *below all of them*. Reconstructed exactly (61.58 → "61.6"):

`WidgetRenderer.kt:186` → `CurrentTempResolver.resolveGraphStyleCurrentTempFromInputs` →
`ActualsAggregator.resolveCurrentObservation` → `ActualTemperatureSeriesBuilder.blendObservationSeries`,
rendered as `lastObservedTemp` (`WidgetRenderer.kt:257`).

| station | raw | resolved | age | decay | weight | share |
|---|---|---|---|---|---|---|
| KSJC (15.9 km) | 66.2 | 66.200 *observed, exact hit* | 0 | 1.000 | 0.00394 | 4% |
| AW020 (2.2 km, PWS) | 64.0 | 63.754 | 5 m | 0.972 | 0.00984 | 11% |
| **KNUQ (3.8 km)** | 62.6 | **61.600** | 20 m | 0.889 | **0.06100** | **66%** |
| KPAO (6.1 km) | 62.6 | 59.233 | 63 m | 0.650 | 0.01772 | 19% |
| LOAC1 (8.3 km, PWS) | 65.0 | 62.994 | 40 m | 0.778 | 0.00056 | 0.6% |

`Σ(w·T)/Σw = 5.73044 / 0.0930567 = 61.58`

Two mechanisms make a sub-minimum result correct, not broken:

1. **Forecast-carried extrapolation** (`extrapolateForward`): a station with no reading at the target
   gets `lastReading + (forecast(target) − forecast(lastReadingTime))`. NWS was falling ~3°/hr that
   evening, so KPAO's 63-minute-old 62.6° resolved to 59.2°.
2. **`personal_station_discount = 95`** cuts PWS weight to 0.05×, so AW020 — the *closest* station at
   2.2 km — carries 11%, while 1/d² leaves KSJC at 4% despite being the only true observation.

Also worth knowing: the dot's **x position and y value come from different stations**. 20:50 is a
candidate timestamp only because KSJC (farthest, 4% of the weight) reported then; the height is
essentially KNUQ's extrapolated 61.6°. Candidate times are event-sampled from raw rows
(`candidateTimes = filtered.map { it.timestamp }`), decoupled from the weights.

Full write-up: `notes/260719-fetch-dot-temperature-derivation.md`.

## Finding 2 — window hypothesis raised, then disproved

Device logs showed the dot at 61.6 and a `TEMP_ACTUALS_DEBUG emit t=20:50` line at 61.1. The dot
blends a 12h/3h context, the graph 72h/60h, so a window effect looked likely.

Tested it properly: `BlendWindowIndependenceTest` + `DeviceBlendFixture` drive **1672 real
observations + 519 forecast rows** captured from the device through the actual shared code, sweeping
context width 2h → 72h. **The dot's timestamp is 61.58° at every width**; dot and graph agree to
`61.580162`. The window is not the cause — those `emit` lines are throttled samples from different
renders (see Finding 4).

## Finding 3 — the real fragility is the caller's QUERY, not the window

`startMs`/`endMs` appear only in the final `if (targetTs in startMs..endMs) result.add(...)`;
`filtered`, `candidateTimes` and `byStation` are built from the **entire** observations list. The
window gates *emission only* and never changes a value — the documented invariant is true, it just
refers to a different knob than it reads like.

What moves values is which rows the caller **queried**. `resolveStationValueAt` needs a reading at or
before the target; with no `before` anchor a station returns null, drops out, and the IDW
renormalises over the survivors:

| region of a narrowly-queried range | worst error vs wide context |
|---|---|
| first 60 min | **up to 5.54°F** |
| beyond 60 min | **0.00°F** — exact |

`maxDeltaAt` landed 0–10 min after the window start at every width tested — purely a leading-edge
artifact.

**No live bug.** Every caller is safe: graph queries 72h; `ActualsAggregator` pads ±24h via
`DAILY_BLEND_CONTEXT_MS`; `YesterdayDeltaCalculator` reads the centre of a ±90 min *emission* window
but is handed the full 72h list; the dot reads its trailing edge. That safety is a property of each
call site, not of the function — nothing documents the precondition, and
`YesterdayDeltaCalculator` would land in the bad zone if a future caller passed it a narrow list.
Suggested follow-ups (**not applied**) in `notes/260719-blend-window-independence-audit.md`.

## Change made — render stamp on TEMP_ACTUALS_DEBUG

`TemperatureStateResolver.kt:534`. Persisted blend-debug lines are **throttled samples** (~8 of 978
per render) and carried no render identity, so two renders' samples were indistinguishable in
`app_logs` and one timestamp appearing with two values read as blend non-determinism. That ambiguity
cost the full reconstruction above.

```
widget=345 source=NWS aligned=2026-07-19T22:00 zoom=NARROW emit t=12:40 blended=67.9 stationCount=5 source=observed
```

Stamped `aligned` and `zoom` as well as the widget id deliberately: the id alone doesn't disambiguate
one widget re-rendering at a different centre or zoom. (The collector already logged a `window
source=… start=… end=…` line — the throttle had simply dropped it from every render sampled.)

**Message shape changed** — queries must not anchor at the start of the message:
`message LIKE 'emit t=20:%'` → `message LIKE '%emit t=20:%'`. No code or scripts consume this tag,
only prose in `notes/` and `session-logs/`.

## Verification

Built and installed to RFCT71FR9NT, triggered a render, pulled the DB: 45 stamped lines under
`widget=345` and 42 under `widget=349` from renders 2 seconds apart — previously one
indistinguishable pile. `:shared:test` 3/3 pass (the third asserts the edge artifact *is* present,
with a message telling whoever fixes it to convert to strict equality).

Note `aligned=22:00` on a 21:37 render is correct, not a clock bug: minute ≥ 30 rounds the centre up
(`ActualsAggregator.kt:51`).

## Incidental

`~/.bashrc` had `JAVA_HOME=$HOME/Downloads/high/android-studio/jbr`, a directory that no longer
exists — every Gradle invocation failed. Repointed to `/usr/lib/jvm/java-21-openjdk-amd64` (Java 21
as required, and it has `jpackage`, which the JBR lacks and desktop packaging needs).

Fixture gotchas for future captures this size: 1672 inlined constructors blow the JVM's **64 KB
`<clinit>` limit** (`Method too large`) — load from a test resource; and use **TSV, not CSV**,
because NWS station names contain commas ("San Jose, San Jose International Airport").

General lesson: a blended value outside the range of every contributing reading is not evidence of a
bug — check whether extrapolation is carrying stale stations along the forecast slope first. See
[blend_window_gates_emission_not_math], [actual_series_event_sampled],
[daily_vs_hourly_actual_extrema_mismatch], [idw_weight_window_dependent_distance].
