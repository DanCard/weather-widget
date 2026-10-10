# Fetch only sources likely to be viewed — summary (2026-10-10)

Plan: `performance/261010-fetch-only-sources-likely-to-be-viewed.md`.

## Decided (user)

- Don't fetch API sources unlikely to be used; fetch on demand when switched to. Losing accuracy
  tracking for unviewed sources is OK.
- Rule: not viewed in 8 days → not fetched in the background (a recency rule, not a probability
  threshold). Refresh / location change skip them too. No Settings line.

## Changed

- `:shared` `SourceFetchGate`: background forecast set (displayed/primary, viewed in last 7 days,
  8-day grace) + derived actuals feeds; `logLine()`.
- Android: `SourceFetchGateLoader` (Room rows → gate, fails open, logs on change);
  `ForecastFetchCoordinator.requiresNetworkFetch` / `visibleSourcesToFetch` take the gate (targeted
  force exempt); `ForecastRepository` computes it per sync; `WeatherWidgetWorker` non-primary branch
  and `MetarObservationRefresher` / `SynopticObservationRefresher.currentTier` use the gated set.
- Desktop: `DesktopSourceFetchGate.otherSources` drives daemon loops 3c (forecasts) and 3d
  (observations).
- CLAUDE.md: one bullet under Weather Data APIs.

## Verification

22 new tests (10 shared, 7 Robolectric, 5 desktop); full suites green (`:app` 2455). Emulator,
seeded to 30 tracked days: gate `on=GOOGLE_WEATHER off=OPEN_METEO,SILURIAN,NWS feeds=NWS`; one API
tap later `on=GOOGLE_WEATHER,OPEN_METEO … feeds=NWS,SYNOPTIC`. Desktop restarted (in grace).

## Insights

- The side agent's NWS-actuals concern was checked twice: the station pull writes NWS's own rows
  only, and every borrower path reads the provider's *observations* via `providerIdFor`. Moving it
  would have added network pulls for rows nobody displays.
- Failing open (no gate → fetch everything) kept the 2455 existing tests unchanged: the in-memory
  test DB has no tracking row, which reads as grace.
- The gate's decisions only change at midnight or on a switch, so per-change logging is ~1–2 lines a
  day.
