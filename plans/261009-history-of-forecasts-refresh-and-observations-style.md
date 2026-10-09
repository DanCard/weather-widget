# History of Forecasts: right-aligned controls, refresh button, Observations style

User, 2026-10-09 (Fold 4): the API button should sit on the right with the gear; add a refresh
button like Current Observations; take the Observations look. Refresh fetches the **viewed source
only**. Android and desktop share as much code as possible.

## Shared (`:shared`)

- `DataScreenStyle` — the data-screen palette (black background, `#121214` cards with a `#2A2A2E`
  border, `#AAAAAA` secondary text, `#4FC3F7` accent, `#0D2B45` source pill). Desktop `ObsStyle`
  now reads it; Android applies it at runtime (`DataScreenStyler`), since layout XML cannot read
  Kotlin constants.
- `ForecastEvolutionCutoff.points(rows, …)` — the rows → graph-points builder (days-ahead filter +
  hindcast cutoff) both history screens used to duplicate.

## Android

- Header: back, ‹ date ›, flex spacer, then [source pill · refresh · gear] as one group that wraps
  together on a narrow screen.
- Refresh: `WeatherRepository.fetchSourceOnDemand(lat, lon, source, request = null)` — the
  single-source fetch a day tap uses (no other source, no quota spent elsewhere). Button dims while
  running; toast "Refreshed {source}" / "Couldn't refresh {source}"; graphs reload; the originating
  widget gets a UI-only repaint.
- Cards, background and the text buttons take `DataScreenStyler`.

## Desktop

- Header: ◀ date ▶ … [source pill · refresh]; second row: title + mode toggle. Uses the
  Observations window's own `SourceCycleButton`, `ObservationRefreshButton` and `DataCard`
  (extracted to `internal` so both windows share them).
- Refresh: `requestSourceRefresh(source)` in the app scope — the displayed source through
  `requestFullRefresh` (that repository is built for it), any other through a repository of its own,
  as the daemon's non-active loop does. The window reloads on `dataUpdateCount`.

## Verified

Fold 4: controls on the right, Observations colours; refresh fetched Open-Meteo (10 → 11 snapshots,
new 09:39 point), toast shown, widget repainted. `:shared` 522 and `:desktop` 55 tests pass; desktop
restarted on the new build (window not visually checked).
