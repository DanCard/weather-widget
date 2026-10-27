# Daily freeze reads the stitched hourly (AtSite noon cloud follow-up)

**User, 2026-10-27:** "fix the AtSite noon cloud follow-up" → "Approve as written"

Follow-up to [plans/261027-pre-hour-freeze-retention-and-shared-pick.md](261027-pre-hour-freeze-retention-and-shared-pick.md)
§0.5 (deferred finding 7).

## Evidence

Desktop DB, `daily_history` for 2026-10-26: OPEN_METEO `noonCloudPercent = 94`
(`lastWriter = forecast_freeze`). That is the 12:27 post-noon hindcast. The graph, stitched with
`SettledForecastPick`, draws the 22:18 pre-noon forecast at 12:00, which is 10%. The frozen value and
the graph disagree for good: the noon-cloud window closed 10-27 08:00.

## Root cause

`DailyHistoryRun.snapshotDisplayedRainChance` hands `planSnapshotDisplayedRainChance` the **live
table only** (`host.hourlyForecasts`). Once a post-noon fetch overwrote noon's live row, no pre-noon
row is left to pick, and `resolveMeasuredNoonCloudCoverPercentAtSite` takes the freshest row, the
hindcast. The rain-chance freeze (`resolveLiveDayNightChanceAtSite`) reads the same rows.

## Fix (`:shared` only)

In `snapshotDisplayedRainChance`, for each source in the day rows, read
`host.hourlyHistory(start, end, lat, lon, source)`. Then pass
`HourlyForecastStitcher.stitchBySource(live, history, nowMs, lat, lon)` as `hourly`, in place of
the live rows. Both Hosts already implement `hourlyHistory` (Room and desktop), so there are no
platform changes. The `AtSite` resolvers then see one stitched row per hour per source, so their
freshest-per-hour step is a no-op, and the frozen values equal what the graph draws.

## Behaviour change

- **Noon cloud** (yesterday and today, while the window is open) freezes at the pre-noon forecast.
- **Day/night rain chance** freezes from the same stitched hours: elapsed hours count their pre-hour
  forecast, not a post-hour revision. This matches the graph and the 2026-10-26 rule ("same for
  temperature, cloud cover and rain %").
- Existing frozen rows are not rewritten. OPEN_METEO 10-26 keeps 94.

## Tests

`DailyHistoryRun` tests with a fake Host, both failing on c8309188:
- Noon live row fetched 12:27 (94%) and history snapshot fetched 11:40 (10%): frozen `noonCloudPercent` = 10.
- An hour forecast at 60% before it and revised to 0% after it: frozen day chance = 60.

## Verify

`./scripts/unit-tests.sh`; parity diff; rebuild and restart desktop; `installDebug`.
