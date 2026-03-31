# API Source Requirement Template

Use this when requesting a new weather API or any new toggleable source.

## Copy/Paste Template

Add `[NEW_SOURCE]` as a weather API.

Requirements:

- Add it as a first-class source in forecast fetch, current-temp fetch, settings, logging, and tests.
- Fresh installs: make `[NEW_SOURCE]` priority position `[N]` in the default visible source order.
- Existing installs: migrate stored source order so `[NEW_SOURCE]` is inserted at position `[N]`, preserving the relative order of all other existing sources.
- If users already have a saved source order, do not leave it untouched; update it via migration.
- If users had explicitly disabled `[NEW_SOURCE]` before this migration existed, specify whether migration should still enable it or respect prior disabled state.
- State whether `[OLD_SOURCE]` should remain enabled by default, become hidden by default, or be removed.
- State whether `[NEW_SOURCE]` requires an API key, and how missing-key behavior should work.
- State whether this change applies only to new installs, or also to upgrades of existing installs.

## Example

Add `OpenWeatherMap` as a weather API.

Requirements:

- Add it as a first-class source in forecast fetch, current-temp fetch, settings, logging, and tests.
- Fresh installs: make `OpenWeatherMap` priority position `2` in the default visible source order.
- Existing installs: migrate stored source order so `OpenWeatherMap` is inserted at position `2`, preserving the relative order of all other existing sources.
- Do not limit this to fresh installs or default prefs only.
- `WeatherAPI` should remain available but not enabled by default for fresh installs.
- `OpenWeatherMap` requires an API key and should fail clearly but not break other enabled sources if the key is missing.

## Short Version

Add `[NEW_SOURCE]` and make it priority `[N]` for both fresh installs and existing installs. For existing installs, migrate saved source order by inserting `[NEW_SOURCE]` at slot `[N]` while preserving the order of all other sources.
