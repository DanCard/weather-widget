# Hourly "Act" rain from the source's actuals provider

**User, 2026-11-14:** "desktop: silurian: why missing actual rain fall amount for yesterday?" and
"Same issue on pixel tablet emulator". Asked what the hourly Act should use: **"Gauges from actual
provider"**.

## Evidence

- Desktop Silurian rain graph, Fri 2026-11-13: "Pred .014in" and "Pred .55in", no "Act" label
  (screenshot). The same gap on the Pixel Tablet emulator (user).
- `daily_history` 2026-11-13: every source, Silurian included, has 7.91 mm (day 7.91, night 0.0).
- Desktop `config.json` `actualsProviders`: OWM, Tomorrow.io, WeatherAPI, Google and Open-Meteo all
  set to NWS. Silurian borrows NWS by default (`ActualsProviderResolver.borrowerDefault`, inside NWS
  coverage).
- The daily blend reads the source's actuals provider (`ActualsAggregator`, via
  `ActualsProviderResolver.providerIdFor`), so it gets 7.91 for every source.
- The hourly graph's Act calls `ObservedPrecip.rowsFor(observations, displaySource)`
  (`desktop/.../PrecipitationGraph.kt:253`, `app/.../PrecipViewHandler.kt:689`). That is the
  **display** source's own series: Silurian and Google (`HistoricalDataKind.NONE`) → empty, so no
  Act. Open-Meteo → `OPEN_METEO_MAIN`, 9.2 mm yesterday, against the NWS gauges the user chose (7.91).
- `HourlyActualsArchiveRun` (line 90) files hourly rain with the same display-source call.

## Change

1. **`:shared` `ObservedPrecip.rowsForActualsOf(rows, source)`:** the rows of
   `ActualsProviderResolver.providerIdFor(source)`, through the existing `rowsFor` rules for that
   provider. For NWS that means its stations without the blend; for METAR or Synoptic, their
   stations; for a source that is its own provider, unchanged.
2. Use it in the desktop `PrecipitationGraph`, the Android `PrecipViewHandler`, and
   `HourlyActualsArchiveRun`. The observation reads must include the provider's rows. Check that each
   loader does not filter by the display source's `api` first; widen it if it does.
3. Docs: the actuals provider section in `arch/` that describes rain Act, if present.

## Tests (each fails on the current code)

1. `ObservedPrecip`: Silurian with provider NWS → NWS station rows; Open-Meteo with provider NWS →
   NWS rows, not `OPEN_METEO_MAIN`; Open-Meteo as its own provider → `OPEN_METEO_MAIN` (unchanged).
2. Hourly Act equals the daily day amount for the same rows (Silurian, 11-13 shape).
3. Android `PrecipViewHandler` and desktop graph: Silurian gets an Act amount.
4. `./scripts/unit-tests.sh`.

## Verification

Desktop Silurian rain graph, yesterday: an "Act" label matching 7.91 mm (≈ .31in) over the day
window. The same on the Pixel Tablet emulator. Open-Meteo's Act matches its daily actual.
