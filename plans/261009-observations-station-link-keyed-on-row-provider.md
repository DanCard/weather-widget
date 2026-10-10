# Observations: station links keyed on the row's provider, not the displayed source

## Symptom
Fold 4, Current Observations, displayed source **Meteo**, Actuals source **NWS**: tapping KNUQ
does nothing. The user expected the KNUQ web page.

## Root cause
`StationHistoryUrl.forStation(sourceId, stationId)` returns a URL only for `sourceId == NWS`.
Android passes the screen's **displayed** source (`currentSource.id`) in both places:

- `WeatherObservationsActivity` adapter click (Current Observations tab)
- `WeatherObservationsActivity` blend-table rows (Blend tab)

Since actuals can be redirected (Meteo → NWS, borrowers → NWS/METAR), the rows on screen are
NWS stations while `currentSource` is Open-Meteo, so every row is unlinked. Same class of bug as
"redirected actuals provider ≠ borrower": group on who *provided* the row, not who is displayed.

Desktop `ObservationsWindow` already passes the row's own `obs.api`, so it links correctly.

## Fix (shared; revised after review)
Desktop's Blend tab (`BlendTableView.BlendRow`) had the same bug — it also passed the displayed
source. So the link decision moves into `:shared`, keyed on each row's own provenance:

- `BlendContribution` gains `api` (the reading's provider), carried through the blend's
  `ContributionMeta`; `MetarCloudBlender` fills it from its anchor reading.
- `BlendTableRow` gains `historyUrl`, computed in `BlendTableFormatter.format` via
  `StationHistoryUrl.forStation(c.api, c.stationId)`. Android and desktop Blend tabs render it.
- Current Observations list: Android passes `entity.api` (desktop already passes `obs.api`).
- `StationHistoryUrl.forStation` parameter renamed `sourceId` → `providerId`, with a doc line:
  pass the row's provider, never the displayed source.

## Tests
- `BlendBreakdownCaptureTest`: an NWS reading blended under display source Open-Meteo yields a
  contribution with `api = NWS` and a row with the timeseries URL; non-NWS rows get none.
- On device (Fold 4): Meteo displayed, actuals NWS → KNUQ opens
  `weather.gov/wrh/timeseries?site=KNUQ` on both tabs. Desktop rebuilt/restarted.
