# Actuals provider choice survives removing a source

Date: 2026-10-31

## Question

If Tomorrow.io's actual temperature source is set to NWS, and Tomorrow.io is then removed and
re-added as a source, does the NWS choice stay?

## Answer

Yes, on both Android and desktop. This comes from reading the code; it was not run on a device.

- **Android:** the choice is stored in its own preference, `actuals_provider_TOMORROW_IO`
  (`WeatherSourcePreferences.setActualsProvider`). Removing or adding a source only rewrites the
  visible-sources order (`SettingsActivity.persistSourceOrder`). The only caller that changes the
  actuals preference is the actuals picker, through `ActualsProviderChangeCoordinator`.
- **Desktop:** the choice is in `settings.actualsProviders`, a separate map from `visibleSources`
  in `DesktopConfig.kt`.

## Exception: outside NWS coverage

Outside NWS coverage, Tomorrow.io temporarily uses its own actuals. The NWS choice stays saved and
applies again back inside coverage (`ActualsProviderResolver.resolve`).

## Resetting the choice

The preference is cleared only when the user picks the default again. For Tomorrow.io, that means
selecting Tomorrow.io as its own actuals source.

claude --resume 6b4c0ca3-ecac-4c2f-b72f-7365144b60b8
