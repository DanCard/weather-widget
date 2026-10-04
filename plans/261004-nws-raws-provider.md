# NWS station list: type RAWS from the `provider` field

## Problem

After `plans/261003-raws-station-type.md`, Synoptic types LOAC1 `RAWS`, but the NWS path types the
same station `PERSONAL`: `NwsApi.classifyStationType` guesses from the id (4 chars starting K/P/T =
official, else personal). The blend treats both alike, so no number changes, but the observations
screen and blend table show one station under two labels.

NWS says the network outright. The station list the app already downloads
(`/gridpoints/MTR/93,87/stations`, 2026-10-04) carries `properties.provider`:
`AW020 APRSWXNET`, `KNUQ ASOS`, `KPAO OTHER-MTR`, `LOAC1 RAWS`, `KSJC ASOS-HFM`, `LAHC1 RAWS`.

## Change

- `classifyStationType(id, provider)`: `provider == "RAWS"` (case-insensitive) → `RAWS`; otherwise
  the existing id rule, unchanged.
- **Not** provider-based for OFFICIAL: KPAO (an FAA airport) is `OTHER-MTR`, so a provider rule
  would demote one of the two nearest official stations. The id rule already gets those right.
- `getObservationStations` passes `provider`. The cached list stores the type by name and decodes
  `RAWS` already; caches refresh within 24 h.
- Stored NWS rows are left to age out (≤10 days): display-only, the blend weight is identical.

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | `getObservationStations` on the recorded list (MockEngine): LOAC1/LAHC1 → RAWS, AW020 → PERSONAL, KNUQ/KPAO/KSJC → OFFICIAL | integration (NwsApi + Ktor + parser) |
| 2 | `classifyStationType`: provider RAWS wins over the id; null/blank provider keeps the id rule | unit |
| 3 | encode/decode round-trip keeps RAWS | unit |

Mutation check: drop the provider branch → #1 and #2 fail.

## Verification (2026-10-04)

- `NwsStationTypeTest` 3/3 on the recorded list; full Android unit suite 4,527 passed; `:shared:test`,
  `:desktop:test` green.
- Mutation check (run): without the provider branch, tests #1 and #2 fail
  (`LOAC1 expected RAWS but was PERSONAL`); #3 (cache round-trip) is a guard and passes either way.
- Desktop restarted on the build. Its cached list (`station_cache`, written 2026-10-03 22:48) still
  types LOAC1 `PERSONAL`, as expected; it flips at the first NWS refresh after the 24 h TTL. The
  cache was not cleared.
