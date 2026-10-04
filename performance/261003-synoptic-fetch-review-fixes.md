# Synoptic fetch: review fixes to the cost-and-resilience implementation

Follow-up to `performance/261003-synoptic-fetch-cost-and-resilience.md` (implemented, uncommitted).

## Live-API evidence (2026-10-03, 37.39,-122.08, 25 mi)

The token's rules require `Origin: https://www.weather.gov` (plus the `Referer` the app sends);
without it every request is `RESPONSE_CODE=2 "Invalid request per token rules"`.

| Request | bytes | stations | rows |
|---|---|---|---|
| today: no `vars`, `recent=1440` | 4,059,655 | 347 | 37,723 |
| `vars=…`, `recent=1440` | 1,226,909 | 225 | 30,711 |
| no `vars`, `recent=120` | 674,131 | 309 | 2,971 |
| **`vars=…`, `recent=120` (routine refresh after this fix)** | **208,843** | 215 | 2,439 |
| `vars=…&limit=10` | 10,202 | 10 | 112 |
| `vars=…&limit=3&network=1,2` | 7,140 | 3 | 31 |

- `vars=` works: derived `_set_1d` names come back unchanged; the `air_temp` QC block survives.
- **`limit=` is not nearest-first** (with or without `sortby=distance`). `limit=10` returned stations
  at 8.3–21.7 mi; the real nearest 10 are all within 4.5 mi (AW020 1.59, G6550 1.70, KNUQ 2.07 …).
- **`network=2` is RAWS, which reports no sky.** The sky query returned LOAC1 (RAWS) and missed
  KPAO. All 9 sky reporters in range are `MNET_ID` 1.

So fix 1 (two small queries) would have replaced the temperature blend's nearest stations with ones
8–22 mi away and dropped KPAO from the cloud curve.

## Changes

1. **Drop the two-query design.** One radius request, `vars=` + gap window, then
   `SkyReportingStationSlots.select` over the full candidate set — equivalent by construction.
   Remove `limit`/`network` from `fetchRadiusTimeseries` (a trap: they do not mean what they look
   like). Delete `SynopticTwoQueryEquivalenceTest`.
2. **Single-flight / freshness floor must span worker runs on Android.** The gate is built per
   `SynopticObservationRefresher`, which is per `WeatherWidgetWorker` run, so the overlapping
   `periodic_60m` / `on_update_stale` pair each had empty state. Move the in-memory state into
   `SynopticFetchGate.Flights`; Android passes one process-wide instance.
3. **Cancellation cannot leak an in-flight entry.** The `finally` removal runs under
   `NonCancellable`; a leaked completed entry would have made every later caller "join" a `null`
   result — Synoptic silently off for that site until process death.
4. **Redact where logs are written, not where messages are made.** `AppLogDao.log`, global `log()`
   (Crashlytics breadcrumbs), `logException` (incl. the Crashlytics exception), desktop
   `DesktopWeatherDao.log`. Regex also catches `access_token=`.
5. **Real fixture for the `vars=` test.** Replace the hand-written "base-name" fixture with KNUQ from
   the recorded full and `vars=` responses.
6. Minor: `FailureClass` drops the bare `"closed"` substring; a transport failure never shortens a
   longer backoff already in force; remove dead `SHALLOW_HOURS`/`DEEP_HOURS`; desktop
   `BORROWED_SYNOPTIC_RECOVERY` stops logging `hours=24` (it is now the gap window).

## Tests

| # | Test | Kind | Proves |
|---|---|---|---|
| 1 | `SynopticFetchGateTest`: two gates sharing one `Flights` → one fetch | unit | state lives in `Flights`, not the gate |
| 2 | `SynopticFetchGateTest`: owner cancelled → next run fetches (no leaked entry) | unit | #3 |
| 3 | `SynopticObservationRefresherSingleFlightTest` (Robolectric): two refresher instances, overlapping `refreshIfDue`, hanging source → 1 call | integration | #2, the 21:58 incident |
| 4 | same: transport failure → `class=transport backoffMin=5` once | integration | one stall = one backoff step |
| 5 | `SynopticApiVarsEquivalenceTest` on recorded KNUQ | unit | `vars=` parses identically |
| 6 | `SynopticObservationFetcherTest`: one request, carries `vars=`, `recent=`, no `limit`/`network` | unit | #1 |
| 7 | `ApiKeyRedactionTest`: `access_token` | unit | #4 |
| 8 | `AppLogDao.log` redacts (Robolectric) | integration | #4 at the write point |

Mutation check: revert each fix and confirm the matching test fails.

## Verification (2026-10-03)

- **Live API:** the table above (one request per shape, desktop host). Routine refresh after this
  fix — one request, `vars=`, gap window ≥120 min — is **209 KB / 2,439 rows** against today's
  **4.06 MB / 37,723 rows** (−95% bytes). Fixtures: `shared/src/test/resources/synoptic/`.
- **Tests:** all pass — `SynopticFetchGateTest` 13, `SynopticApiVarsEquivalenceTest` 3 (recorded
  fixtures), `SynopticObservationFetcherTest` 2, `ApiKeyRedactionTest` 14, `FailureClassTest` 8,
  `SynopticObservationRefresherSingleFlightTest` 3 (Robolectric: refresher + gate + prefs + Room).
  `:shared:test`, `:desktop:test` green.
- **Mutation check (run, not asserted):**

  | Reverted | Failing test |
  |---|---|
  | `flights = FLIGHTS` on Android | overlapping worker runs → `expected:<1> but was:<2>` |
  | redaction in `AppLogDao.log` | `app_logs never stores a credential` (token in row) |
  | `NonCancellable` cleanup | cancelled owner → "leaked in-flight entry" |
  | `maxOf` on transport backoff | `expected:<22600000> but was:<1300000>` |

- `SynopticTwoQueryEquivalenceTest` deleted; the speculative `_set_1` parse fallback removed (live
  `vars=` returns the same `_set_1d` names, and the real `_set_1` siblings such as
  `cloud_layer_1_code_set_1` carry numeric codes the parser would misread).
- Not changed, noted: `parseRadiusTimeseries` maps `MNET_ID` 2 (RAWS) to `OFFICIAL`; its comment
  calls 1 and 2 "the NWS/FAA networks". RAWS are well-sited government stations, so the blend weight
  is defensible, but the comment is wrong.
