# Prefer the official METAR over the ASOS 5-minute sample

**Date:** 2026-08-21
**Commit:** `c70a015f` (unpushed)
**Files:** `NwsApi.kt`, `ForecastTypes.kt`, `MetarCloudBlender.kt`, `NwsObservationMapper.kt`
(shared), `ObservationEntity.kt`, `WeatherDatabase.kt`, `NwsObservationSource.kt` (app),
`DesktopEntities.kt`, `DesktopWeatherDao.kt`, `DesktopWeatherDatabase.kt` (desktop),
`MetarCloudBlenderTest.kt`, `NwsApiCloudLayersParseTest.kt`, `NwsCloudActualsRoundTripTest.kt`
**Issue:** `issues/260821-cloud-actual-curve-drew-the-wrong-sky.md` — defect 4, the one left open
by `5400e3f3`

## Problem

`api.weather.gov/stations/{id}/observations` interleaves **two different instruments** in one array:

| Feed | Cadence | `rawMessage` | What it measures |
|---|---|---|---|
| METAR | `:53` + specials | populated | Official sky cover — a **30-minute rolling** ceilometer assessment |
| ASOS 5-minute | every `:00/:05/:10…` | **empty** | **Instantaneous, single-point** sample directly overhead |

`MetarCloudBlender` picked each station's contribution with
`minByOrNull { abs(it.timestamp - hourMs) }`. The 5-minute feed publishes a row **exactly on the
hour mark** (distance 0); the METAR sits at `:53`, 7 minutes away. So at any station publishing
both, the METAR could **never** be selected — a blender named for METARs was not using one.

That matters because the 5-minute reading is the wrong answer to "how cloudy is it". Its `CLR`
arrives with `base: 3810` m — the ceilometer's **detection ceiling** (≈12,500 ft) — and means
*"nothing overhead at this instant"*, not *"the sky is clear"*. Under scattered cloud the beam
passes in and out and the value flips `CLR`↔`SCT` (0↔44) minute to minute while the station's own
METAR steadily reads `SCT040`.

**Measured at KSJC, 2026-08-21 00:00–05:05:** 60 of 66 samples read `OVC`. The isolated `BKN` dips
at 00:30 and 03:50 were exactly the values the graph drew as real hourly dips at 1a and 4a.

## Why the discriminator had to be persisted

The intuitive shortcut — *"METARs are at odd minutes, 5-minute rows at multiples of five"* — is
**wrong**. KSJC and KPAO report at `:53`/`:47`, but **KNUQ's METARs land on `:15`/`:35`/`:55`**:
multiples of five, indistinguishable from 5-minute rows by timestamp alone.

Only `rawMessage` reliably separates them, and the blend reads from `observations` long after the
payload is gone — so the flag had to be stored. That is what turned a selection tweak into a schema
change.

## Changes

1. **`NwsApi` parses `rawMessage`** into `Observation.isMetar`, in **both** parsers
   (`parseObservationProperties` and `getLatestObservation`).
2. **`observations.isMetar` persists it** — Room `MIGRATION_63_64` (v64) and desktop
   `SCHEMA_VERSION = 18`, both `INTEGER NOT NULL DEFAULT 0`.
3. **`MetarCloudBlender` prefers it per station**: filter to cloud-carrying rows → prefer the METAR
   class if non-empty → nearest-to-the-hour decides *within* the chosen class.
4. **New `metarPreferred=` counter** in the `CLOUD_SERIES` stats line.

### Deliberate properties of the rule

- **The preference selects a class, not a row.** Among several METARs, nearest-to-mark still
  decides.
- **Per-station.** One station having a METAR must not suppress another that only has 5-minute
  rows; blend width is unaffected.
- **A partial METAR does not blank the hour.** The carrier filter runs *before* the preference, so
  a METAR missing sky condition yields to a cloud-carrying 5-minute row rather than dropping the
  station from the blend.
- **Existing rows read `false` and behave exactly as before.** Backfilled as 0 rather than guessed
  — the rule above cannot re-derive it and the raw payloads are gone. The preference fades in as
  fresh rows arrive instead of writing a wrong guess into history.
- **`metarPreferred` near zero at an airport station** means `rawMessage` is not arriving and the
  curve has quietly reverted to instantaneous samples.

## The bug this turned up

The Room round-trip test failed with `expected:<75> but was:<0>`.
`NwsObservationSource.toEntity` hand-copies `ObservationReading` field by field into
`ObservationEntity`, and silently dropped `isMetar`.

This is the **third** instance of the same duplication in one investigation, after the two DAOs
(defect 1) and the two parsers (defect 2). Every one failed by *omission*, which no compiler
catches.

Worth noting how it was caught: **every unit test around it passed** — the parser set the field, the
blender preferred it. Only a test that wrote through the real entity conversion and read back a
**value** could see the gap.

The other `ObservationEntity` constructors were audited: `CurrentObservationReader`,
`CurrentTempRepository` and `HourlyForecastStore` build synthetic `NWS_BLEND` and `<SOURCE>_MAIN`
rows, which are never METARs, so the `false` default is correct there.

## Verification

**Tests** — `:app:testDebugUnitTest`, `:shared:test`, `:desktop:compileKotlin` all green.

| Test | Pins |
|---|---|
| `MetarCloudBlenderTest` (5 cases) | METAR beats an on-the-mark sample; nearest-to-mark still decides among METARs; no-METAR and partial-METAR buckets still contribute; preference stays per-station |
| `NwsApiCloudLayersParseTest` | METAR / 5-minute / field-absent payloads map to the right `isMetar` |
| `NwsCloudActualsRoundTripTest` | Through real Room: 75 (the METAR) beats 0 (`CLR` on the mark) |

3 of the 5 blender cases were confirmed to **fail with the preference removed** — the other 2 cover
fallback paths that are correctly unchanged.

**On-device** — Room migrated to `user_version=64` and desktop to `18`, both with existing rows
intact. Both platforms now show the expected asymmetry, where KNUQ (which publishes only METARs)
gets flagged rows and KSJC (whose recent rows are 5-minute samples) does not:

| Platform | KNUQ | KSJC |
|---|---|---|
| Desktop | 2 of 6 rows flagged | 0 of 14 |
| Android | 1 of 6 rows flagged | 0 of 4 |

Confirmed against the live endpoint: KNUQ's latest carries
`rawMessage: "KNUQ 211515Z AUTO 00000KT 10SM OVC013 16/13 A3006 RMK AO2"`, KSJC's is empty.

## Follow-ups

Both carried over from the issue doc, neither started:

1. **Unify `getLatestObservation` with `parseObservationProperties`.** It would remove this class of
   bug outright, but it also starts populating `precipLastHourMm` and 24h min/max on the live path.
   `DailyActualsStore` sums precip across observations, so it needs its own rain-total assessment.
2. **Investigate the live path's row density.** It stores ~1 row per station per fetch while the
   history backfill stores dense 5-minute rows. With 60 `OVC` samples available, an hourly value
   should not be decided by whichever single flicker happened to be persisted.
