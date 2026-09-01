# METAR physical-plausibility QC — session digest

*2026-08-31. Plan: [`plans/260831-metar-physical-plausibility-qc.md`](../plans/260831-metar-physical-plausibility-qc.md)*

## What happened

Reported symptom: on the Samsung, the hourly graph's actual line read 62.9° while the on-graph
station label read `knuq 68° @ 5:15 pm`.

Pulled the device DB rather than reading source. The blend was not wrong — its input was. `KPAO`
(5 km) had served a corrupt METAR at 16:47:

```
15:47  KPAO 312247Z 33018G20KT 10SM SCT040 21/12 A2993   -> 69.8F
16:47  KPAO 312347Z 32014G22KT 10SM SCT040 10/12 A2993   -> 50.0F   <-- corrupt
```

`10/12` is dewpoint 12 °C above a temperature of 10 °C — thermodynamically impossible. Synoptic's own
QC caught it (`qcFailed=1`); the `api=NWS` web-fallback copy of the *identical string* was stored
`qcFailed=0`, because `MetarObservationMapper` set `qcFailed = false` unconditionally. A 50 °F
station blended against real 66–72 °F neighbours is the whole defect.

Scanning all 7,229 stored METARs for the same shape turned up a second, unreported corruption:
`KRHV 2026-08-27`, `209/14` — a three-digit temperature field, which upstream salvaged as `09` ->
9 °C -> 48.2 °F between neighbours of 66.2 °F and 73.4 °F. Our own `MetarDecoder` finds *no* match in
that string, so it needed its own structural rule.

## The measurement that changed the design

The user's instinct — and mine — was a temporal spike check ("70 -> 50 all of a sudden"). Measured
against the corpus, it does not survive:

- as a **rate** (>=15 °F/hr): **1,096 hits**, because Open-Meteo's 15-minute sampling inflates °F/hr
  on ordinary evening cooling;
- as an **absolute delta** (>=18 °F/75 min): 3 hits, but it flags KRHV *twice* — going in and coming
  back out — and cannot say which side is the bad reading.

It is also unsound in principle: a marine push or gust front genuinely moves temperature that fast,
and the KPAO report carries `G22KT` gusts. Rejecting only what is *impossible* needs no history, no
state, and no window.

Final rules, measured over 7,229 rows: **3 flagged, all 3 genuinely corrupt, 0 false positives.**

## What changed

- **New** `shared/…/observations/MetarPlausibility.kt` — pure, shared by both platforms.
  Dewpoint > temperature; malformed `T/Td` group; a −90..140 °F backstop. The rejected
  "stored-vs-raw disagreement" rule is documented in the plan as measured-and-dropped (0 hits).
- `MetarObservationMapper.toReading` — the unconditional `qcFailed = false` now carries the verdict.
- `NwsObservationMapper.toReading` — verdict ORed with NWS's own code, so a garbled report with a
  clean `V` is still caught.
- `WeatherDatabase` v69 -> **v70**, `MIGRATION_69_70`, and desktop `SCHEMA_VERSION` 23 -> **24** —
  data-only repair re-running the check over stored rows. Future-only would have left the reading
  poisoning the 72-hour hourly window for days, and the sticky daily-low ratchet could have latched
  it permanently.

No blend change was needed: `ActualTemperatureSeriesBuilder.kt:307` already filters `!it.qcFailed`.

## Verification

Full results in the plan. Unit + instrumented suites green; a mutation probe (neutering `check()`)
fails 8 tests, confirming they can fail. On device: `user_version` = 70, both corrupt rows flagged,
exactly 2 rows newly flagged DB-wide, no collateral. User confirmed the widget reads correctly.

## Regression caught after the first pass

I ran `:shared:test` and `:app:testDebugUnitTest` but not `:desktop:test`, and the desktop
`SCHEMA_VERSION` 23 -> 24 bump broke `DesktopObservedCloudSchemaTest`, which asserted the literal
`23` after `initialize()`. That test is about the v22 cloud columns, not the version number, so the
fix was to make `SCHEMA_VERSION` public and assert against it — the next bump cannot break it the
same way. The desktop counterpart to the Android migration test was also missing and has been added
(`DesktopMetarRequalificationSchemaTest`), with the same mutation probe confirming it can fail.

**Lesson: a shared-module change needs all three suites — `:shared`, `:app`, `:desktop`.**

## Not done

Nothing committed — per standing preference, commits happen only when asked.
