# RAWS as its own station type, treated like personal

## Problem

`SynopticApi.parseRadiusTimeseries` maps `MNET_ID` 1 **and 2** to `OFFICIAL`; its comment calls both
"the NWS/FAA networks". Live data (2026-10-03) shows 2 is **RAWS** — fire-weather stations on
passive radiation shields, often on hills. On today's hot afternoon, vs the bay-shore airports
(KNUQ/KPAO), 1–5 pm:

| | Δ °F |
|---|---|
| personal stations 1.6–4 mi | +4.7 … +6.8 |
| KSJC (official, 8.8 mi inland) | +4.5 |
| LOAC1 (RAWS, 4.2 mi) | **+11.8** (+14.6 at 1 pm) |
| other low RAWS (PUGC1, CXOC1) | +10 … +12.5 |

As OFFICIAL, RAWS always gets full blend weight while the user's personal-station discount
down-weights the (cooler) personal stations — the discount favoured the hottest station. NWS's own
path already types LOAC1 `PERSONAL` (only 4-char K/P/T ids are official).

## Change (user's design)

1. `NwsApi.StationType` gains `RAWS`. Synoptic: `1 → OFFICIAL`, `2 → RAWS`, else `PERSONAL`. Comment
   fixed.
2. One predicate, `StationTypes.isDiscounted(type)` in `:shared` = `PERSONAL` or `RAWS`, used by the
   two `== PERSONAL` consumers: the blend (`ActualTemperatureSeriesBuilder`) and
   `PersonalStationThinning`. Consumers that test `== OFFICIAL` (daily station extremes, hourly
   backfill, desktop now-dot popup, row colours) already put RAWS with personal — unchanged.
3. Display: blend table letter `F` (fire-weather) on its own legend line — `R` is already the
   value column's "real reading". Observations screens print the raw type, so rows read `RAWS (…)`
   in the personal colour with no UI change.
4. **Stored rows.** ~10 days of RAWS rows are stored as `OFFICIAL`, the blend reads the stored
   type, and the gap window never re-fetches them. After every Synoptic store, retag that batch's
   stations' earlier SYNOPTIC rows to the type just fetched (Android `ObservationDao`, desktop
   `DesktopWeatherDao`). Self-correcting, exact (uses `MNET_ID`, not an id heuristic), cheap
   (≤ ~13 stations per fetch, only rows whose type differs).

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | Synoptic parse: MNET 1 → OFFICIAL, 2 → RAWS, 65 → PERSONAL | unit |
| 2 | Blend: a RAWS candidate gets `personalStationWeight`, identical to the same reading as PERSONAL | unit |
| 3 | Thinning: RAWS rows thinned like PERSONAL | unit |
| 4 | `StationDailyExtremes` ignores RAWS | unit |
| 5 | Android retag: stored `OFFICIAL` LOAC1 row → `RAWS` after a Synoptic store; KNUQ untouched; NWS LOAC1 row untouched | integration (Robolectric: refresher + DAO + Room) |
| 6 | Desktop retag via `DesktopWeatherDao` | integration |
| 7 | Blend table letter `F` | unit |

Mutation check each.

## Verification (2026-10-03)

- All new tests pass; `:shared:test`, `:desktop:test` green.
- Mutation check (run):

  | Reverted | Failing test |
  |---|---|
  | `"2" -> OFFICIAL` | parse: `expected:<{…LOAC1=RAWS…}>` |
  | `isDiscounted` = PERSONAL only | blend `expected:<77.67> but was:<78.2>`; thinning RAWS case |
  | refresher uses plain `insertAll` | Android retag: old LOAC1 row stays `OFFICIAL` |

- Not covered by a test: the desktop repository's call to `retagStationType` (the DAO is covered by
  `DesktopStationTypeRetagTest`). **Checked live** after restart: the first Synoptic refresh
  (23:55:38) moved all 122 stored `SYNOPTIC|LOAC1` rows from `OFFICIAL` to `RAWS`; the 302
  `NWS|LOAC1|PERSONAL` rows were untouched.
- Full Android unit suite: 4,524 tests. The first run failed one localization guard
  (`HardcodedUserFacingStringTest`: the new legend line is unlocalized prose); added to its
  allowlist with the same reason as the two existing legend lines, then green.
- `StationDailyExtremes` (RAWS ignored) is a guard test — it passes before and after, since that
  consumer already compared `== OFFICIAL`.
