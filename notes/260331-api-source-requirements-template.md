# API Source Requirement Template

Use this when requesting a new weather API or any new toggleable source.

## Copy/Paste Template

Add `[NEW_SOURCE]` as a weather API.

Requirements:

- Add it as a first-class source in forecast fetch, current-temp fetch, settings, logging, and tests.
- Fresh installs: make `[NEW_SOURCE]` priority position 2 in the default visible source order.
- Existing installs: migrate stored source order so `[NEW_SOURCE]` is inserted at position 2, preserving the relative order of all other existing sources.
- If users already have a saved source order, do not leave it untouched; update it via migration.

## Example

Add Visual Crossing as a weather API.

Requirements:
- Add it as a first-class source in forecast fetch, current-temp fetch, settings, logging, and tests.
- Fresh installs: make `Visual Crossing` priority position `2` in the default visible source order.
- Existing installs: migrate stored source order so `Visual Crossing` is inserted at position `2`, preserving the relative order of all other existing sources.
- Do not limit this to fresh installs or default prefs only.

## Short Version

Add `[NEW_SOURCE]` api and make it priority `[N]` for both fresh installs and existing installs.
For existing installs, migrate saved source order by inserting `[NEW_SOURCE]` at slot `[N]` while preserving the order of all other sources.

### Example

Add `Visual Crossing` api and make it priority `2` for both fresh installs and existing installs.
For existing installs, migrate saved source order by inserting `Visual Crossing` at slot `2` while preserving the order of all other sources.

