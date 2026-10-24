# Hourly forecast snapshots: one shared window rule, local-time anchored, by view and battery

User, 2026-10-24, answering the bucket divergence found by the Host contract work
(`plans/261024-host-contract-tests-room-and-desktop-sqlite.md`, `ElapsedForecastBackfill.Store`):

> 4 hours for the displayed source, 12 hours for non displayed source if charging or battery above
> 70%. Otherwise roughly every 24 hours. I don't want clock drift so if optimal time is 4 am for
> example then o.k. 4 am every day rather than drifting later all the time. No update if source is
> unlikely to be viewed based on past usage.

Plan only; no code changed.

## How snapshots work today (both platforms)
A snapshot is not scheduled. Every fetch writes the source's next-72 h hourly into
`hourly_forecast_history` under a **window** key (`timestampToGroupPredictions`); a later fetch in the
same window replaces it (`INSERT OR REPLACE`, the key includes the window). So a window holds **the
last forecast fetched before it closes**, and the window width sets how many snapshots per day
survive, not how often anything runs.

| | Android | Desktop |
|---|---|---|
| Rule | `ForecastHistoryPolicy` (`:app`) | `(now / 4 h) * 4 h`, written 3× in `DesktopWeatherRepository` |
| Displayed source | 4 h | 4 h |
| Other sources | 12 h | 4 h |
| Battery | ignored | ignored |
| Window edges | UTC multiples: 4 h at 01/05/09/… PDT; 12 h at 05/17 PDT, 04/16 PST — **they move with DST** | same (4 h only) |
| Label | window start | window start |

Android also uses the same window to dedupe daily `forecasts` rows (`ForecastSnapshotStore.deleteForecastsInBucket`).
Desktop: ~5 windows/day per source (measured: 13–15 per source over 3 days, 900–1,250 rows each).

## Proposed rule — `:shared` `SnapshotWindow` (replaces `ForecastHistoryPolicy` and desktop's 3 copies)
| Source | Charging or battery ≥ 70 % | Battery < 70 % |
|---|---|---|
| Displayed (Android: any widget's) | 4 h | 4 h |
| Other, likely to be viewed | 12 h | 24 h |
| Other, unlikely to be viewed | no snapshot | no snapshot |

- **Anchored to local time, fixed edges** — no drift, DST-safe: 4 h windows end at
  00/04/08/12/16/20, 12 h at 04:00 and 16:00, 24 h at 04:00. They nest (04:00 is an edge of all three).
  04:00/16:00 match what the snapshots are for: "yesterday's forecast" is taken from fetches before
  06:00 (low) and 16:00 (high) the previous day (`PriorDayForecast`), so a window closing at 16:00
  keeps exactly the last forecast before the high's cutoff.
- Battery tier from the shared `BatteryTier` (≥ 70 % or charging), read at write time — the same
  threshold as the forecast cadence.
- "Displayed" never skips and stays 4 h whatever the battery (on battery < 70 % the cadence fetches
  ~daily anyway, so 4 h costs nothing extra).

### Label each window by its **end** — decided (user, 2026-10-24)
Wide windows make a start label lie. A 24 h window `[04:00 D−1, 04:00 D)` labelled 04:00 D−1 holds
the last fetch before 04:00 D; a fetch at 03:00 D for 10:00 D (7 h ahead) would be filed as made
30 h ahead, and the prior-day band reader (`bucket ≤ hour − 24 h`) would take it as a day-ago
forecast. Today's 12 h windows already have up to 12 h of this. Labelled by its end, a window's label
is never earlier than its contents, so "made at least 24 h before" and "captured on the previous day"
(the two readers, `PriorDayBandForecast`, rain accuracy in `HistorySnapshotRetention`) can only err
conservatively. Existing rows keep their start labels and age out in 30 days.

### "Unlikely to be viewed"
Two signals exist; the user picks:
- **A (recommended):** `:shared` `SourceViewProbability.sourceViewedToday` below a threshold
  (proposed 10 %). Recency-weighted over the last 30 days of switches; logged daily
  (`SOURCE_VIEW_PROBABILITY`) but not acted on yet. A source you switch to every few days stays in.
- B: `SourceFetchGate`'s "not viewed in 8 days". Already stops background fetches of such sources, so
  this would only skip the snapshot of a targeted fetch (a switch, a day tap) — almost no effect.

Cost of skipping: if you later switch to a skipped source, its "yesterday's forecast" bar and the
rain-accuracy columns are empty for the days it was skipped (never back-filled — the provider does
not re-send past predictions).

## Changes
- `:shared` `SnapshotWindow.label(nowMs, zone, sourceId, displayed: Set<String>, batteryHigh: Boolean,
  likelyViewed: (String) -> Boolean): Long?` (null = do not snapshot) + `width(...)`; logs
  `SNAPSHOT_WINDOW source=… width=4h|12h|24h|skip reason=…` (VERBOSE, INFO on skip).
- Android: `HourlyForecastStore` (2 writers), `ForecastSnapshotStore` (daily dedupe window) call it;
  `ForecastHistoryPolicy` deleted.
- Desktop: the 3 hard-coded windows in `DesktopWeatherRepository` call it (displayed = the popup's
  source; battery from `PowerDetector`).
- Elapsed-hour backfill (`ElapsedForecastBackfill.Store`) files under the same label; skipped when the
  source would not be snapshotted.

## Tests
- `:shared` `SnapshotWindowTest`: edges in America/Los_Angeles across both DST changes (no drift:
  same local edges on 2026-03-07/08/09 and 2026-10-31/11-01/02); nesting; battery tiers; skip;
  end label ≥ every fetch filed under it.
- A reader case that fails on today's start label: a 03:00 fetch for 10:00 is not taken as a day-ago
  band forecast.
- Both platforms' writers file under `SnapshotWindow` (Room + desktop SQLite, extending the
  `ElapsedForecastBackfillStoreContract` / a new writer case).

## Expected effect (desktop, today's 5 sources, NWS displayed)
Non-displayed sources ~5 → 2 windows/day on the charger (~1 on battery), so `hourly_forecast_history`
grows ~55 % slower for them; fewer if A skips rarely-viewed sources. Android unchanged on a charger
except the anchored edges and the < 70 % tier.

## Decisions needed
1. ~~Label by window end (recommended) or keep the start?~~ **End** (user, 2026-10-24).
2. ~~"Unlikely to be viewed": A or B?~~ **B, not viewed in 8 days** (`SourceFetchGate`; user, 2026-10-24).
3. Anchors — **reopened** (user: "Do we really need windows? Can it be more ad hoc?"). See below.
4. ~~Displayed source on battery < 70 %?~~ **4 h always** (user, 2026-10-24).
5. ~~Can the **displayed** source skip when unlikely to be viewed?~~ **No, always saved** (user, 2026-10-24). The first version of the answer read
   "No update if displayed source is unlikely to be viewed". The signal would be the hours of the
   week the device goes unused (Android `UsageHours`, phase 1, log-only; desktop has none — its away
   state already stops fetching). Cost: no "yesterday's forecast" for days whose window fell in an
   unused stretch.
6. Android's daily `forecasts` rows are deduped by the same window (`deleteForecastsInBucket`): follow
   the new rule too (recommended, one rule), or keep 4 h / 12 h there?
7. ~~Threshold under A~~ — moot (B chosen).

## Proposed instead of windows: ad hoc, exact labels (awaiting the user)
Each fetch's snapshot is labelled with its own fetch time and never overwritten; a write-time check
decides whether to save it at all:

| Source | Rule |
|---|---|
| Displayed | every fetch |
| Other, viewed in the last 8 days | only if this source's last snapshot at the site is ≥ ~11 h old (charging or ≥ 70 %), ≥ ~22 h (< 70 %) |
| Other, not viewed in 8 days | never |

- Labels are exact, so decision 1 (start vs end) no longer applies; the readers cannot be fooled.
- Skipping writes nothing; a window overwrite still writes the 72 rows — only skipping saves writes.
- Thresholds a little under 12 / 24 h follow the battery-driven fetch schedule without creeping later
  each day (≥ 24 h would: 04:10 one day forces > 04:10 the next). No anchors to choose.
- Cost: the displayed source keeps every fetch's 72 rows until the daily prune (`HistorySnapshotRetention`,
  charger + screen off) instead of one per 4 h window. Desktop already fetches ~every 4 h (little change).
- One indexed "last snapshot for this source here" read per fetch per non-displayed source.
- Android's daily `forecasts` thinning (`deleteForecastsInBucket`) would keep every fetch, as desktop does.


**Decided (user, 2026-10-24): ad hoc.** Decisions 3 (anchors), 6 (Android daily rows keep every fetch)
follow from it.

## Done (2026-10-24)
- `:shared` `HourlySnapshotRun` (+ Host): the table above, label = fetch time; a call within 5 min of
  the source's last snapshot reuses its label (one fetch = one snapshot, so the elapsed-hour backfill
  never reads its own fetch as recent); fails open; logs `HOURLY_SNAPSHOT source=… decision=save|skip
  reason=displayed|due|recent|not_viewed|same_fetch lastAgeMin=… gapH=…` (VERBOSE for displayed /
  same_fetch, INFO otherwise).
- Android: `RoomHourlySnapshotHost` (displayed = any widget, viewed = `SourceFetchGateLoader`, battery =
  `BatterySnapshotProvider`) → `HourlyForecastStore.snapshotLabel` (live snapshot + elapsed backfill).
  `ForecastHistoryPolicy`, `ForecastDao.deleteForecastsInBucket` and the daily-row thinning removed.
- Desktop: `DesktopHourlySnapshotHost` (displayed + gate from the config file, battery from
  `PowerDetector`); the three hard-coded 4 h windows in `DesktopWeatherRepository` replaced;
  repositories built with `snapshotConfig = { DesktopConfigStore().load() }`.
- DAO read `getLatestSnapshotLabel` / `getLatestHourlySnapshotLabel` (same-site), pinned by
  `HourlySnapshotHostContract` (Room + desktop SQLite).
- Tests: `HourlySnapshotRunTest` (7: table, 11 h / 22 h edges, same-fetch reuse, no creep on a
  12-hour-ish schedule, fail-open, log), `DesktopHourlySnapshotRuleTest` (4, Host + gate + DAO + run),
  contract 2 + 2. Updated for the new behaviour: `OpenMeteoIntegrationTest` (label = fetchedAt),
  `OpenMeteoCloudLayerStorageIntegrationTest` (newest of two snapshots), `ForecastDeduplicationBugReproTest`
  (a row per changed fetch), `ForecastSnapshotHindcastCutoffTest` (fixture row the old thinning deleted).
- One decision per fetch on Android: `HourlyForecastStore.decideSnapshot` keeps the live snapshot's
  decision for the elapsed-hour backfill within `SAME_FETCH_MS` (the emulator logged each skip twice
  before; `HourlyForecastStoreElapsedBackfillTest`).

## Verified on devices (2026-10-24)
| Case | Where | Logged | Rows |
|---|---|---|---|
| Displayed source | desktop 15:46, emulator 16:00 / 16:16 | `save reason=displayed` | 72 under the fetch time |
| Other, last ≥ 11 h, battery 100 % | emulator 16:00 | `save reason=due lastAgeMin=660 gapH=11` | 72 each |
| Other, last < 11 h | desktop 16:00 (Open-Meteo) | `skip reason=recent lastAgeMin=420 gapH=11` | none |
| Other, battery 50 % unplugged | emulator 16:16 | `skip reason=recent lastAgeMin=15 gapH=22` | none |

Labels land 1–56 ms after the rows' `fetchedAt` (never before the forecast they hold). Emulator
battery faked with `dumpsys battery` and reset after; it recorded one `UNPLUG_RECORDED` (n=1).
