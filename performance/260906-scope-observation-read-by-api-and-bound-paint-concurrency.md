# Scope the observation read by `api`, and bound paint concurrency

**Date:** 2026-09-06
**Status:** done — both parts implemented, unit-tested and measured on the Samsung

Follow-on from
[260906-samsung-tap-latency-observation-read-cost.md](260906-samsung-tap-latency-observation-read-cost.md),
which fixed the two costs that bought nothing (lazy pool diagnostics, gated extrema diagnostic) and
left the read itself at ~4.4 s cold. This plan does the two changes chosen from that plan's options
list: **the scoped read** (replacing its mis-framed option C) and **option D, bounded paint
concurrency**.

## Part 1 — scope the observation read by `api`

### Why the previous option C was wrong

That plan proposed downsampling Synoptic, on the grounds that Synoptic is 68% of the `observations`
table at 330 rows per station-day. The user's question — *"the API was on NWS, shouldn't Synoptic
data be ignored in this case?"* — is the correct objection. It should be, and it already is:

```kotlin
// ObservationSourceMatcher.matchesActualSource:138
val providerId = ActualsProviderResolver.providerIdFor(source, actualsPreference)
if (api != providerId) return false
```

Its KDoc calls this "THE shared predicate" — the daily blend, the hourly actual curve and the cloud
series all reach it via `ActualTemperatureSeriesBuilder.matchesObservationSource`, and
`HourlyObservationBackfill` filters its coverage check the same way (lines 133, 169). For a widget
displaying NWS, every `api='SYNOPTIC'` row is rejected.

Nor does the NWS blend need them: the five stations in `IDW_BLEND source=NWS [KSJC, AW020, LOAC1,
KNUQ, KPAO]` each have their own `api='NWS'` rows. Synoptic is a **second transport** carrying
duplicate copies of four of them plus eleven mesonet stations NWS never reports.

Rows in one 132 h read window on the Samsung:

| api | rows | text bytes |
|---|---:|---:|
| **SYNOPTIC** | **34,726 (70%)** | **1,353,764** |
| NWS | 7,862 (16%) | 339,227 |
| OPEN_METEO | 6,304 (13%) | 196,916 |
| TOMORROW_IO | 531 (1%) | 14,730 |

So on a paint of an NWS widget, **70% of the rows and 71% of the text come off disk only to be
discarded by the first filter in the blend.** The filter is correct; it just runs on the wrong side
of the CursorWindow. `ObservationDao` takes `(startTs, endTs, lat, lon)` and no source, so `api`
cannot be expressed in SQL even though every caller knows it.

The predecessor plan established the remaining cost is **disk, not CPU** — cold `sql=2438 ms` vs warm
`sql=353 ms` at identical row counts. Fewer bytes is the whole lever.

### The Silurian correction (this is what makes it non-trivial)

`actuals_provider_SILURIAN = SYNOPTIC` is stored in `widget_state_prefs.xml`. **Silurian takes its
actuals from Synoptic**, so Synoptic rows are load-bearing whenever Silurian is displayed. The set to
keep is therefore never a literal — it is always `ActualsProviderResolver.providerIdFor(source)`,
which consults the stored per-source preference.

Active display sources on the reporting device, and what each resolves to:

| display source | provider (`api` to keep) |
|---|---|
| NWS | `NWS` |
| OPEN_METEO | `OPEN_METEO` |
| SILURIAN | **`SYNOPTIC`** (stored preference) |
| TOMORROW_IO | `TOMORROW_IO` |

Consequence: for a **multi-source** caller on this device the union is all four apis and the scoped
read saves nothing. The win is on the **single-source paint path**, which renders one display source
at a time — NWS alone is 7,862 of 49,423 rows, an **84% cut**.

### Change

1. **`ObservationDao`** — add an api-scoped candidate query beside the existing one:

   ```sql
   SELECT * FROM observations
   WHERE timestamp >= :startTs AND timestamp < :endTs
     AND ${LocationMatch.ROOM_WHERE}
     AND api IN (:apis)
   ORDER BY timestamp ASC, stationId ASC, locationLat ASC, locationLon ASC
   ```

   The `ORDER BY` must stay byte-identical to `getObservationCandidatesInRange`'s — it is a TOTAL
   order on all four primary-key fields, and its comment records why (row order leaks into
   `groupBy { stationId }`, which decides `dominantStationByDay`'s tie-break and `anchorStation`;
   the same rows in a different order rendered two alternating series and made the high/low labels
   blink). See `ActualsRowOrderDeterminismTest`.

   `readObservationsInRange(..., apis: Set<String>? = null)` — `null` keeps today's unscoped
   behaviour, so every existing caller is untouched by construction.

2. **`ForecastRepository` / `WeatherRepository`** — thread the optional `apis` through.

3. **`TemperatureStateResolver:628`** — pass
   `setOf(ActualsProviderResolver.providerIdFor(displaySource), WeatherSource.GENERIC_GAP.id)`.
   `GENERIC_GAP` is included because `matchesActualSource` admits it ahead of the provider check
   (`allowGenericGap`, default true). `NWS_BLEND` and `<SOURCE>_MAIN` synthetic rows carry a real
   `api` and stay filtered downstream by station id, as today.

Both consumers of that read — `buildHourDataResult` and `maybeEnqueueHourlyObservationBackfill` —
already filter by the same predicate, which is why scoping is safe *here* specifically.

### Deliberately NOT scoped

- **`DailyActualsStore.recomputeDailyExtremesForDay`** — emits `DAILY_HISTORY_STABLE` for NWS,
  OPEN_METEO, SILURIAN, SYNOPTIC and TOMORROW_IO. It genuinely needs the union. (Open question below.)
- **`getCloudActuals`** — separate DAO method routing through `MetarCloudBlender.fromSiteRows`, which
  resolves its own provider and has METAR transport-duplicate handling. Out of scope; revisit only
  with its own measurement.

### Verification

- **Unit:** a DAO test asserting the scoped and unscoped queries return identical rows when the api
  set is the union of what is present — the scoping must be a filter, never a reordering.
  Pair with `ActualsRowOrderDeterminismTest`.
- **On device, per the user's instruction: test with Silurian selected**, since it is the one source
  whose provider is not itself. Expected: with SILURIAN displayed the read keeps `api='SYNOPTIC'` and
  the actual temperature curve and daily bars are unchanged from before the change. A blank or
  truncated actual line under Silurian is the failure signature.
- Also A/B NWS: `OBS_RANGE_READ` should show `candidates` drop from ~33k to ~8k with `merged`
  materially unchanged, and `TEMP_PIPELINE_PERF obsQueryMs` fall on a **cold** read (drop the page
  cache or force-stop + `am start` between trials — a warm read already costs only ~350 ms and will
  hide the effect).
- Screenshot both sources before/after.

## Part 2 — bound paint concurrency (option D)

### What is already there

`WidgetInteractionCoordinator` holds a per-widget `Mutex`, so actions for **one** widget are already
serialized, and `WidgetActionJobRegistry` deliberately does *not* cancel superseded jobs (its KDoc
says cancel-by-name would be wrong under that mutex). Neither bounds work **across** widgets.

`WidgetActionReceiver` uses `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — unbounded. With
three widgets, one tap fans out to three concurrent paints, each issuing its own multi-thousand-row
cold read; the 2026-07 investigation recorded the same herd ("15 broadcasts <100 ms still spike
~1.4 s and defeat the cache"). Measured this session: 18 reads >300 ms in a single cycle summing
50.4 s of work, several with identical `candidates`/`merged` counts — i.e. the *same* read, run
concurrently by different widgets, each paying full cost and evicting the others' pages.

### Change

- Introduce one shared bounded dispatcher for widget paint work —
  `Dispatchers.IO.limitedParallelism(n)` (kotlinx 1.7.3, available) — and use it for
  `WidgetActionReceiver.scope` and the worker's paint fan-out. Start at **n = 2**, chosen so a tap
  cannot be starved behind three simultaneous cold reads while still overlapping I/O with CPU.
- Do not reduce work; this only stops taps queueing behind a running sync, which is the user's
  actual complaint ("very slow on taps").

### Risks

- `WidgetInteractionCoordinator`'s mutex is held *inside* these coroutines. A bounded dispatcher plus
  a held mutex is a deadlock shape if any path ever acquires a second widget's mutex while holding
  one. Confirm no nested acquisition before landing.
- `goAsync()` has a ~10 s budget; queueing behind a bounded dispatcher must not push a broadcast past
  it. `CLICK_WATCHDOG` already fires at 8000 ms and is the signal to watch — it appeared 78 times in
  three days *before* this change, so compare against that baseline, not against zero.

### Verification

- `CLICK_WATCHDOG` count should not rise; `*_SLOW` counts should fall.
- Burst test: fire several `ACTION_TOGGLE_API` broadcasts <100 ms apart across all three widget ids
  and compare summed `OBS_RANGE_READ` total against the current 50.4 s/cycle.

## Order

Part 1 first — it reduces the work each paint does, which is what makes bounding concurrency cheap
rather than a queue. Land and measure them separately so the attribution stays clean.

## Open question for the user

The daily recompute computes `daily_history` for **SYNOPTIC as its own source**, yet Synoptic is not
among the active display sources (NWS, OPEN_METEO, SILURIAN, TOMORROW_IO) — it is only reachable
indirectly, as Silurian's actuals provider. If that per-source row is incidental rather than
deliberate, scoping the recompute to active display sources would cut the union it must read.
Not assumed either way here.


## Outcome

Both parts landed. Measured on SM-F936U1 (3 widgets, 80k observations, 71 locations), against the
figures in the predecessor plan:

| | original | after lazy-diag + gated-diag | **after this plan** |
|---|---:|---:|---:|
| tap paint `TEMP_PIPELINE_PERF totalMs` | 7,975 ms | 863 ms | **342–502 ms** |
| `obsQueryMs` | 5,462 ms | 570 ms | **87–124 ms** |
| actuals `recompute` | 26,780 ms | 4,610 ms | **1,907 ms** |
| `widgets` paint stage | 8,639 ms | 6,337 ms | **813 ms** |
| full sync total | 52,162 ms | 28,524 ms | **9,017 ms** |

End to end that is **~20× on the tap path** and **5.8× on the full sync**.

### Part 1 verification

- `ObservationDaoApiScopeTest` — four cases: scoping to every present api reproduces the unscoped
  rows *in the same order*; scoping to one api preserves that api's relative order; the merge yields
  the same survivors either side of the filter; an absent api yields empty rather than falling back.
  **Mutation-tested** — changing the scoped query's `ORDER BY` to `timestamp ASC, stationId DESC`
  fails exactly the two ordering cases, so the assertions are real.
- Full `:app:testDebugUnitTest` green. Two Robolectric tests
  (`TemperatureDeltaVisibilityRoboTest`, `TemperatureFetchDotUpdateRoboTest`) stubbed
  `readObservationsInRange` with an explicit `null()` matcher for the new parameter and needed
  `any()`; that is a fixture change, not a behaviour change.
- **On device, per-source `obsRows` now reflect the scope**: NWS 1,601 · SILURIAN 6,049 ·
  OPEN_METEO 290 · TOMORROW_IO 131. Previously every one of these read the same ~33k candidates.
- **Silurian confirmed working** (the case that would break if the filter were a literal):
  `DOMINANT_STATION widget=345 source=SILURIAN station=KNUQ weightShare=0.500 obsRows=6049` and
  `IDW_BLEND source=SILURIAN stations=13 blendedPoints=307`. All 13 blend stations were verified to
  carry `api='SYNOPTIC'` rows, including KSQL (7 rows) and LOAC1 (2) which are mostly METAR/NWS
  elsewhere. Screenshot of the NWS graph shows actual curve, hindcast, forecast, station label and
  Now indicator intact.

### Part 2 verification

- Burst test — 9 `ACTION_TOGGLE_API` broadcasts (3 widgets × 3 rounds) fired back to back from a
  cold-started process: worst read **566 ms**, and **`CLICK_WATCHDOG` fired 0 times** against a
  baseline of 78 in three days.

### Two findings that changed the design mid-flight

1. **The worker's fan-out was already sequential.** `WidgetPaintCoordinator.updateAllWidgets` is a
   plain `for` loop over widget ids; its only `launch` is the fire-and-forget GPS resample. So the
   herd was *entirely* receiver-side — one broadcast per widget, each `onReceive` launching its own
   coroutine on unbounded `Dispatchers.IO`. The plan said to bound both; bounding the worker would
   have been a no-op, and **sharing one pool would have been actively harmful** — a 30-second sync
   could occupy every slot and starve the taps the change exists to protect. `WidgetInteractionDispatcher`
   therefore bounds the interaction path only, and the worker keeps its own dispatcher.
2. **The deadlock risk did not materialise.** `forEachWidgetIsolated` is sequential and every
   `withWidgetLock` call site takes exactly one widget id, so no coroutine ever holds two widget
   locks. Recorded in the dispatcher's KDoc so a future concurrent fan-out has to re-check it.

### Still open

- The daily recompute remains unscoped by design (`apis=ALL` in its `OBS_RANGE_READ` lines) — it
  computes `daily_history` for every source at once. It is now the largest remaining reader.
- Synoptic volume, deferred by the user. Analysis in the predecessor plan's discussion: the station
  limit is the wrong knob (`DEFAULT_LIMIT = 10` plus the `MIN_SKY_STATIONS = 3` top-up yields 11;
  lowering it to 8 yields 9 and saves ~5%, and is fragile because KPAO sits exactly on the boundary).
  The real weight is that **12 personal stations hold 80.4% of Synoptic rows, contribute zero sky,
  and are weighted 0.05** under `personal_station_discount = 95`. Thinning their sampling rate is the
  candidate, not the station count.
