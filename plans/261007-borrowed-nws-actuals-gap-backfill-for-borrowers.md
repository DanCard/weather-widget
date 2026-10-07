# Borrowed NWS actuals: gap backfill must run for borrowing sources

## Symptom (emulator, 2026-10-07 07:15)
Google Weather hourly graph, "Actual temperature data from NWS": the pink actual line is flat from
~03:00 to ~06:30, then drops in one straight step to the 06:55 reading. Looks like missing resolution.

## Evidence
- Host suspended 03:26 → ~07:12 (`journalctl`: `systemd-logind … The system will suspend now!`), so
  the emulator was frozen; `app_logs` has nothing between 03:20 and 07:12. The hole itself is expected.
- NWS rows in `observations` at the site: AW020 03:00 → 06:40, KNUQ 02:55 → 06:35, KSJC 03:00 →
  06:55. Nothing in between. The 07:13 current fetch stores only the newest row per station.
- The repair that should fill it never ran:
  `OBS_HOURLY_BACKFILL_SKIP widget=69 source=GOOGLE_WEATHER reason=provider_history_in_forecast`
  (three times after resume). Synoptic, by contrast, back-filled with `recentMin=271`.

## Root cause
`evaluateHourlyBackfillNeed` (`HourlyObservationBackfill.kt`) branches on the **display** source.
GOOGLE_WEATHER / SILURIAN fall into `provider_history_in_forecast` and return "no backfill" — but
since 8fdc912e a borrower's actuals inside NWS coverage **are NWS observations**
(`ActualsProviderResolver.providerIdAt`). The NWS gap rules (`max_gap_min > 75`, `latest_gap_min`,
`day_start_uncovered`) never see the borrowed series. Same class as
[[redirected_actuals_provider_not_borrower]]: key on the provider, not the display source.

## Fix
In `maybeEnqueueHourlyObservationBackfill`, resolve the actuals provider at the fetch site:
`actualsSource = WeatherSource.fromId(ActualsProviderResolver.providerIdAt(displaySource, lat, lon))`
and pass it to `evaluateHourlyBackfillNeed` (and to `hourlyBackfillSourceKey`, so NWS-displayed and
Google-displayed widgets share one cooldown for the same NWS data). Log both
(`source=GOOGLE_WEATHER actuals=NWS`). The rest is unchanged: when the provider is NWS the existing
NWS gap logic and `enqueueRequiredObservationBackfill` run; when it is METAR/Synoptic/Open-Meteo the
existing non-NWS branches decide as today (Synoptic already fills gaps via `recentMin`).

Desktop: check whether its borrowed-NWS path (`BORROWED_NWS_RECOVERY`) has the same display-source
gate after a resume; mirror the fix there if so.

## Tests
- `evaluateHourlyBackfillNeed`/decision test: GOOGLE_WEATHER display, NWS provider, NWS rows with a
  3.5 h hole → request with `max_gap_min`. Fails on current code.
- GOOGLE_WEATHER with an explicit METAR preference → no NWS request (unchanged behaviour).
- Cooldown key for Google-borrowing-NWS equals NWS's.

## Verify
Emulator: install, confirm `OBS_HOURLY_BACKFILL_REQ source=GOOGLE_WEATHER actuals=NWS
reason=max_gap_min=…`, then NWS rows 03:00–06:35 appear and the pink line regains its shape.
