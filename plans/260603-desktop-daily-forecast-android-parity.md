# Desktop Daily Forecast Android Parity

## Summary
Bring the desktop daily forecast view to Android parity for navigation, header behavior, responsive resizing, and daily graph semantics. Treat Android daily widget behavior as the source of truth, but keep desktop inside its Compose/JVM boundary: no Android `Context`, `RemoteViews`, Room, or widget prefs in `:desktop`.

## Key Changes
- Replace the desktop fixed `7` daily columns with Android-equivalent sizing:
  - Derive `cols`, `rows`, `isIconWidth`, and graph-vs-text behavior from the Compose window size using the Android formulas: 70dp cell width, 90dp cell height, `+15/+25` rounding, graph mode for approximately 2+ rows.
  - Use shared `NavigationUtils` with the derived column count, including skip-yesterday behavior, offset windows, and left/right availability.
  - Clamp visible daily data to the Android window and reset/maintain `dateOffset` when resizing makes the current offset invalid.

- Rework desktop daily navigation controls:
  - Use real icon buttons with stable hit zones at left/right edges, matching Android enabled/disabled behavior.
  - When unavailable, keep the button visible but disabled/low-emphasis rather than disappearing, so resizing does not shift graph content.
  - Left/right actions update `DesktopConfig.dateOffset` by exactly one day.

- Build a desktop daily parity model:
  - Add pure JVM desktop daily view-model code that converts `ForecastResult`, cached forecasts, forecast snapshots, daily actuals, hourly cloud/precips, current temp, and current date into Android-like day columns.
  - Support today triple bars, past actual bars plus forecast overlays, future forecast bars, source-gap/climate-normal fallback markers when desktop data has them, day/night rain labels, cloud-cover mixed bars, current temp header fields, and date/API source text.
  - Extend desktop DAO/repository reads to expose forecast batches grouped by target date from the existing `forecasts` table; no schema migration unless inspection during implementation shows an existing column cannot represent required data.

- Rebuild `DailyForecastGraph` around the Android renderer layout rules:
  - Port the Android layout constants and algorithms into Compose/Skia equivalents: top padding, temp range, label scaling/shortening, icon/day-label stack, bar widths, today triple offsets, rain-label collision handling, and header-date suppression.
  - Draw the Android-style header inside the graph region for daily graph mode: weather icon/current temp/delta/precip on the left, centered or right-shifted date, API source/settings on the right, with progressive disclosure for narrow widths.
  - Keep the existing desktop top status bar and location/settings affordances only where Android has no direct widget equivalent; avoid duplicating the daily graph header.

- Add focused tests:
  - Pure unit tests for desktop size-to-columns/rows, navigation bounds, visible date windows, disabled nav states, and resize offset clamping.
  - DAO/repository tests for forecast snapshot retrieval from multiple `forecasts` batches.
  - Compose/UI tests that verify header text/buttons render, daily resize changes column count, left/right buttons update `dateOffset`, and one-row daily mode uses text layout while taller windows use graph layout.
  - Run focused desktop/shared tests first, then `./gradlew test` if the focused suite is clean.

## Assumptions
- Full parity means Android daily graph behavior is authoritative, but desktop remains a native Compose window rather than embedding Android bitmap rendering.
- Existing desktop SQLite forecast history is sufficient for snapshot overlays; only DAO/model additions are expected.
- Desktop source support remains limited by what each shared API client already returns; unavailable Android-only data should degrade explicitly rather than fabricate values.
