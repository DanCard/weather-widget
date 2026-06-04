# Desktop Actuals Graph Android Parity

## Summary

Make the desktop hourly temperature actuals line use the same data-preparation semantics as Android before drawing. The current mismatch is caused by desktop drawing raw `ObservationReading` rows directly, while Android blends observations, injects sub-hourly actual points into the hourly timeline, carries forward recent actuals, and clips/anchors the line at the observed transition point.

## Key Changes

- Add a pure JVM shared actual-temperature series builder in `:shared`.
  - Inputs: shared `HourlyForecast`, shared `ObservationReading`, source id, user lat/lon, center time, `now`, zoom back/forward hours, and Android-equivalent lookback/lookahead hours.
  - Behavior: preserve Android's source filtering, non-NWS single-station selection, IDW/interpolation/extrapolation blending, sub-hourly actual injection, prior-window last-actual carry-forward, and observed-vs-filled flags.
  - Output: ordered graph points with `timeMs`, `forecastTemp`, `actualTemp`, `isActual`, `isObservedActual`, plus blend stats/debug summary.
- Refactor Android to delegate only the actual-series computation to `:shared`.
  - Keep Android-specific icon, sun/twilight, label, `HourData`, and renderer code in `:app`.
  - Map shared graph points back into `HourData.actualTemperature`, `isActual`, and `isObservedActual`.
  - Keep existing Android rendering behavior as the parity source of truth.
- Update desktop `TemperatureGraph` to draw from the shared prepared series.
  - Replace the direct `rawObservations.sortedBy(timestamp)` actual-line path with shared prepared actual points.
  - Draw the pink actual line only through prepared actual points up to the shared transition/observed endpoint.
  - Keep Compose-only rendering details such as Canvas, icons, labels, fill, and forecast line styling unless they directly conflict with actual-line parity.
- Keep current public app behavior stable.
  - No database schema changes.
  - No config file changes.
  - No change to desktop refresh/storage shape; `ForecastResult.rawObservations` remains the desktop input source.

## Test Plan

- Move or duplicate the core `ObservationBlender` regression coverage into `:shared` tests so Android and desktop use the same verified logic.
- Add shared tests for:
  - multi-station blended observed points,
  - interpolated historical gaps,
  - forecast-guided extrapolated points,
  - non-NWS station selection,
  - last-actual carry-forward across sparse past hours,
  - parity-shaped output for a fixed hourly/observation fixture.
- Update Android tests around `TemperatureHourDataBuilder` to assert it preserves current `HourData` actual fields after delegating to shared logic.
- Add a desktop unit test that builds the same fixed fixture and asserts the desktop graph-prep path receives the same actual point times/temps as Android.
- Run focused verification:
  - `./gradlew testDebugUnitTest --tests com.weatherwidget.widget.handlers.TemperatureViewHandlerActualsTest`
  - `./gradlew :shared:test`
  - `./gradlew :desktop:test`

## Assumptions

- "Parity" means the desktop actuals line should match Android's data shape and transition behavior, not necessarily every Android label-placement or bitmap-renderer detail.
- Android remains the source of truth for actual-line semantics.
- Desktop should keep using shared `ForecastResult.rawObservations`; the fix is how those observations are prepared for graphing, not how they are fetched or persisted.
