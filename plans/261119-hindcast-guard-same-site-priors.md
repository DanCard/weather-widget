# Hindcast guard: read priors from the same site, not only the exact key

## Problem (Fold 4, 2026-11-19)
Today's OWM column drew the current-forecast bar as a dot: the newest row was high 53.13 / low 52.88.

## Evidence
- `forecasts`, OPEN_WEATHER_MAP, target 2026-11-19 (backup `backups/20261119_231113_sm-f936u1_RFCT70G352Y`):

  | fetched | key | high / low | hindcast |
  |---|---|---|---|
  | 06:44 | 37.417,-122.088 | 54.34 / 50.09 | |
  | 13:28 | 37.417,-122.088 | 53.22 / 50.09 | low 53.06 |
  | 16:26 | 37.417,-122.088 | 53.22 / 50.09 | 54.03 / 53.44 |
  | 17:16 | 37.416,-122.072 | 53.20 / 52.99 | |
  | 17:19 | 37.409,-122.080 | 51.75 / 51.10 | |
  | **17:25** | **37.417,-122.089** | **53.13 / 52.88** | |
  | 19:38 | 37.417,-122.089 | 53.13 / 52.88 | 51.67 / 51.67 |

- `app_logs`: every fetch at a new key logs
  `SNAPSHOT_HINDCAST_ACCEPTED ... prior_age_h=null` (OWM, NWS and Google at 17:16; OWM at 17:19/17:25).
- OWM's 17:25 payload only had today's 19:00–23:00 hours (52.9–53.3): `/2.5/forecast` sends future
  3-hour steps only, so after the cutoffs its "today" is the rest of the evening.

## Root cause
`DailyForecastWrite.plan` settles today's high/low (`SameDayExtremeCutoff.settle`) against priors read on
the site's **exact quantized key** (D1 of `plans/261024-shared-forecast-persist-rules.md`). A GPS fix
0.001° away (-122.088 → -122.089, ~90 m — jitter, not a new place) is a new key with no prior, so the
guard treats the prior as missing and accepts the hindcast. The 16:26 row on the neighbouring key held
53.22 / 50.09; with it as a prior the 17:25 write would have kept that.

## Fix (user chose "same-site guard", 2026-11-19)
- `DailyForecastWrite.Stored` gains `exactKey` (default true).
- Both platform readers (`storedForecastsAtSite` on Room, `storedAtSite` on desktop JDBC) return this
  source's rows on the **same site** (`LocationMatch.sameSite`, ±0.002° ≈ 200 m — jitter only, not D1's
  rejected ±0.1° box) and flag the exact-key ones.
- `plan` uses all same-site rows only for today's prior extremes: the cutoff guard and the kept today
  low. Dedup/restamp, upgrade, and summary carry-forward stay on exact-key rows, so writes and restamps
  never touch a neighbouring key.
- Narrows D1: a jitter fragment is the same site's prior for the guard; a different place still is not.

## Tests
- `DailyForecastWriteTest`: a same-site neighbour's pre-cutoff row holds today's high/low after the
  cutoff, and is never restamped (fails on old code: no `exactKey`; behaviour: accepted hindcast).
- `ForecastPersistHostContract` (Room/Robolectric + desktop SQLite): daily reads return same-site
  fragments flagged `exactKey=false` and exclude a farther site; end to end through
  `ForecastPersistRun`, a 13:00 fetch on one key then a 17:00 fetch on the jitter key keeps the 13:00
  high, with the sent one in `hindcastHighTemp`.

## Docs
`arch/forecast-and-observation-cadence.md` (Started hours and stale forecasts): priors are read across
the same site.

## Verification
`./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`; after install, Fold 4 /
Pixel `SNAPSHOT_*` lines show `SNAPSHOT_SKIP_HINDCAST` rather than `prior_age_h=null` after a jitter fix.
