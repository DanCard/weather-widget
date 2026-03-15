# Default Silurian Off For New Installs

## Problem
- Silurian API forecast horrible.  Says 87% cloud cover when there isn't a cloud in the sky.

## Summary
- Change the fresh-install source default so `SILURIAN` starts disabled.
- Preserve existing-user migrations and any already-saved source selections.

## Key Changes
- Update `WidgetStateManager` so the default `visible_sources_order` is `NWS,WEATHER_API,OPEN_METEO`.
- Keep the existing Silurian migration logic for installs that already have a stored source order and need `SILURIAN` appended.
- Leave `SettingsActivity` behavior unchanged so users can enable Silurian manually later.

## Test Plan
- Fresh install with no stored `visible_sources_order` returns `NWS`, `WEATHER_API`, `OPEN_METEO`.
- Existing stored source order still gets `SILURIAN` appended by migration.
- Saved source lists still round-trip without changing order unless a migration applies.

## Assumptions
- “Default to off” means excluded from the visible/enabled source list on new installs only.
- Existing installs should keep current migration behavior.
