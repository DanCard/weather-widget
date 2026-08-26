# Hourly temperature graph: borrowed-actuals source label

## Goal

On the hourly temperature graph, when the selected weather source borrows its actual
temperature data from a different provider (Open-Meteo / Silurian -> METAR / Synoptic),
show a low-priority annotation:

> Actual temperature data from <provider>

Mirrors the existing cloud-cover graph annotation
(`actual_cloud_cover_data_from`), which already names the borrowed actuals feed.
The text is dropped whenever no empty band fits.

## Root cause / context

- `ActualsProviderResolver.borrows(source)` is true only for sources with no observation
  product of their own; `providerIdFor(source)` returns the actual provider id
  (METAR default, or the installed preference such as SYNOPTIC).
- The cloud-cover graph already renders this via
  `CloudCoverViewHandler.localizedActualsSourceLabel()` +
  `DominantStationLabel.place(...)` (returns null -> not drawn when no room).
- The temperature graph had only the dominant-station label (`knuq 73.4° @ ...`), which is
  null for borrowing sources (synthetic backfill), so borrowed actuals were unlabelled.

## Changes

### Android

- New string `actual_temperature_data_from` in `values/strings.xml` + all 19 locale files.
- `TemperatureStateResolver`: `temperatureActualsSourceLabel()` helper; computes the label
  only when `ActualsProviderResolver.borrows(displaySource)`; passes it to the renderer.
- `TemperatureGraphRenderer`: new `actualsSourceLabel` / `onActualsSourcePlaced` facade
  params, forwarded to the annotation renderer and placed last in the free-floating-label
  priority ladder.
- `TemperatureGraphAnnotationRenderer`: new `placeActualsSourceLabel()` — same gates as the
  dominant-station label (`no_text` / `too_few_hours` / `span_too_wide` / `no_empty_band`),
  drawn in the small actual-pink station paint, registered as `ACTUALS_SOURCE`.
- `TemperatureGraphModels` / `TemperatureGraphObstacleRegistry`: `ActualsSourceDebug` and
  `ACTUALS_SOURCE` obstacle type.

### Desktop

- `TemperatureGraph.kt`: same annotation after the ghost-line labels (lowest priority),
  using `DominantStationLabel.plainLabelText("Actual temperature data from ...")` and
  `DominantStationLabel.place(...)`; drawn only when placement succeeds.

### Tests

- `TemperatureActualsSourceLabelLocalizationRoboTest` (German localization).
- `TemperatureActualsSourceLabelRoboTest` (drawn on clear plot + clear of nav arrows;
  suppressed for wide windows / null label; never overlaps the dominant-station label).

## Note

`TemperatureGraphRendererArchitectureTest` was removed per request after the new facade
plumbing pushed the file past its 350-line guard; the renderer still delegates all drawing
to the extracted collaborators.
