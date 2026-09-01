# METAR physical-plausibility QC — reject corrupt station temperatures

*2026-08-31 — Samsung SM-F936U1, Mountain View*

## Problem

The hourly graph's actual (pink) line plunged from 70.1° to 62.6° between ~15:40 and 17:00 while the
on-graph station label read `knuq 68° @ 5:15 pm`. Every real station in the area read 66–72°F. The
blended "now" dot showed 62.9°.

## Root cause

`KPAO` (Palo Alto, 5.02 km) reported a corrupt METAR at 16:47 and it entered the IDW blend at full
weight:

```
15:47  KPAO 312247Z 33018G20KT 10SM SCT040 21/12 A2993   -> 69.8F   (21C)
16:47  KPAO 312347Z 32014G22KT 10SM SCT040 10/12 A2993   -> 50.0F   (10C)  <-- corrupt
```

The `T/Td` group reads `10/12`: **dewpoint 12 °C above an air temperature of 10 °C**, which is
thermodynamically impossible — dewpoint is by definition the temperature at which the parcel
saturates, so it can never exceed the air temperature. The true value was almost certainly `20/12`
(68 °F, matching KNUQ exactly); a leading digit was garbled in transmission.

A 50 °F station 5 km out, blended against real 66–72 °F neighbours, is what dragged the line down
~5 °F.

**Two independent write paths, one flagged and one not.** Synoptic's own QC caught the reading and
we stored `qcFailed=1` for the `api=SYNOPTIC` copy. But the `api=NWS` web-fallback presentation
copy of the *identical METAR string* was stored `qcFailed=0`, because
`MetarObservationMapper.toReading` sets `qcFailed = false` unconditionally (its comment explains the
feed's own `qcField` has undocumented scale, so it is deliberately ignored). We apply no
plausibility check of our own. `ActualTemperatureSeriesBuilder.kt:307` already filters
`!it.qcFailed`, so flagging the row correctly is sufficient to fix the graph — no blend change
needed.

**A second, independent corruption of a different shape** was found in the same corpus:

```
07:47  KRHV 271447Z 00000KT 10SM SCT080  19/13   -> 66.2F
08:47  KRHV 271547Z 00000KT 10SM FEW080 209/14   -> 48.2F  <-- corrupt, 3-digit temp group
09:47  KRHV 271647Z 35003KT 10SM SCT080  23/14   -> 73.4F
```

`209/14` is structurally invalid — a METAR temperature field is `M?\d{2}`. Upstream's decoder took
the trailing `09` -> 9 °C -> 48.2 °F and we inherited the value. Our own `MetarDecoder`
(`BODY_TEMP_DEWP_REGEX`) finds *no match at all* in that string, so a dewpoint check alone would not
catch it — it needs a separate structural rule.

## Rules chosen, and why these

Validated against all 7,229 stored rows carrying a `rawMetar`:

| Rule | Hits / 7229 | What it caught |
|---|---|---|
| Dewpoint > temperature | 2 | Both copies of the KPAO reading |
| Malformed `T/Td` group (temp field not `M?\d{2}`) | 1 | The KRHV reading |
| Absolute range outside −90..140 °F | 0 | (cheap backstop, cannot false-positive) |
| *Rejected:* stored temp disagrees with raw group by >1 °C | 0 | added nothing; unproven risk |

**Three rows flagged, all three genuinely corrupt, zero false positives.**

### Why not the "sudden jump" check

The obvious instinct is a temporal spike test ("70 -> 50 all of a sudden"). Measured against the same
corpus, it is not viable:

- As a **rate** (>=15 °F/hr over <=1.5 h gaps): **1,096 hits.** Open-Meteo's 15-minute sampling
  inflates the rate on entirely ordinary evening cooling.
- As an **absolute delta** (>=18 °F within 75 min): 3 hits — the KPAO reading, plus *both* KRHV
  transitions (66.2->48.2 going in and 48.2->73.4 coming out). So it flags one corrupt reading as
  two violations and cannot tell which side is the bad one.

It is also unsound in principle here: a Bay Area marine push or a gust front genuinely can drop
temperature ~20 °F in an hour, and this very METAR carries `G22KT` gusts. A spike check would reject
real weather. The two rules above reject only what is *physically or structurally impossible*, need
no history or state, and are pure functions of a single reading.

## What will change

1. **New** `shared/src/main/kotlin/com/weatherwidget/shared/observations/MetarPlausibility.kt` —
   pure object, `check(temperatureF, rawMetar): Verdict(failed, reason)`. Shared so Android and
   desktop get identical behaviour.
2. `MetarObservationMapper.toReading` — replace the unconditional `qcFailed = false` with the
   plausibility verdict. This is the origin of both observed defects (the `api=METAR` standalone row
   and the `api=NWS` web-fallback presentation copy derived from it).
3. `NwsObservationMapper.toReading` — OR the verdict into the existing
   `qcFailed = observation.qcFailed`, so an NWS API row carrying the same corrupt `rawMessage` is
   caught too.
4. **One-shot re-QC of stored rows.** The bad KPAO row is still in the DB. Today's NWS
   `computedLowTemp` is 57.3 °F, so the 50 °F has not latched into the daily low yet — but the daily
   low uses a sticky ratchet, so a later recompute would latch it permanently. A maintenance pass
   re-runs the check over retained rows with a `rawMetar` and sets `qcFailed`.

## Tests

| # | Kind | Test | Asserts |
|---|---|---|---|
| 1 | Unit | `MetarPlausibility` rejects dewpoint above temperature | The real KPAO `10/12` string fails with reason `dewpoint_above_temp` |
| 2 | Unit | `MetarPlausibility` rejects a malformed temp/dewpoint group | The real KRHV `209/14` string fails with reason `malformed_temp_group` |
| 3 | Unit | `MetarPlausibility` accepts the valid neighbours | KPAO `21/12` and KRHV `19/13`, `23/14` all pass |
| 4 | Unit | `MetarPlausibility` accepts sub-zero and missing-dewpoint forms | `M05/M12`, `10///`, absent group — all pass (no false rejects) |
| 5 | Unit | Range backstop | 200 °F fails; −40 °F and 120 °F pass |
| 6 | Integration (mapper + plausibility) | `MetarObservationMapper` marks the corrupt row `qcFailed` | Row built from the real KPAO METAR has `qcFailed = true`; the 15:47 row does not |
| 7 | Integration (mapper + plausibility) | `NwsObservationMapper` ORs plausibility with upstream QC | Corrupt `rawMessage` fails even when upstream `qualityControl` is `V` |
| 8 | Integration (blend + QC) | `ActualTemperatureSeriesBuilder` ignores the flagged row | Blend with the KPAO 50 °F row flagged returns a value inside the 66–72 °F station spread |

## Verification

**Implemented 2026-08-31.** Test #8 needed no new test — `ActualTemperatureSeriesBuilderTest`
already pins it (`qc-failed reading is excluded from the blended series`), with the same station and
the same 50 °F value, from a July incident.

| Check | Result |
|---|---|
| `MetarPlausibilityTest` | 10 tests, 0 skipped, 0 failed |
| `MetarObservationMapperTest` | 15 tests (2 new), all pass |
| `NwsObservationMapperMetarTest` | 9 tests (2 new), all pass |
| `:shared:test` full suite | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` full suite | BUILD SUCCESSFUL |
| `WeatherDatabaseMigrationTest` on emulator | 17 tests, all pass (incl. new `migrate69To70_…`) |
| `:desktop:test` full suite | BUILD SUCCESSFUL |
| `DesktopMetarRequalificationSchemaTest` (new) | Desktop half of the migration test — passes |
| Mutation probe | Neutering `check()` to always pass fails 8 shared tests and the desktop migration test; the "must still pass" tests stayed green |

### On device (Samsung SM-F936U1)

`installDebug`, launch, re-pull DB:

- `PRAGMA user_version` = 70 — the migration ran.
- `KPAO 2026-08-31 16:47 api=NWS`: `qcFailed` 0 -> **1** (dewpoint rule). The `api=SYNOPTIC` copy was
  already 1 from Synoptic's own QC.
- `KRHV 2026-08-27 08:47 api=METAR`: `qcFailed` 0 -> **1** (malformed-group rule).
- Whole retained DB: 6 rows flagged, of which exactly **2 were newly flagged by this migration** —
  the two above. The other 4 are pre-existing Synoptic flags on rows carrying no raw report, left
  untouched. No collateral.

**User confirmed the widget reads correctly on the Samsung.**

### Regression caught after the first pass

`:desktop:test` was not run in the first round and `DesktopObservedCloudSchemaTest` failed on the
`SCHEMA_VERSION` 23 -> 24 bump: it asserted the literal `23` after `initialize()`, though that test
is about the v22 cloud columns and not the version number. `SCHEMA_VERSION` is now public and the
test asserts against it, so the next bump cannot break it the same way. A desktop counterpart to the
Android migration test was missing too and has been added.

### Follow-up worth considering (not done)

Neither corrupt reading was rejected by the provider that served it to us — Synoptic caught the KPAO
one, `aviationweather.gov` served both unflagged. If garbled reports turn out to be more than a
twice-a-fortnight event, the natural next step is an `OBS_QC_REJECT` app_log at the flagging site, so
the rate is measurable rather than only discoverable by noticing a bent graph.
