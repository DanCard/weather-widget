# Sim loop follow-ups: availability, viewer translations, rain amount formatting

Plan: `plans/261020-sim-loop-availability-viewer-i18n-precip-amount-format.md`.

- Pill hidden off the WeatherNext map and for 24 h after a 404/410 (both platforms, shared rule).
- Viewer text in 20 locales (31 strings); verified in German on the emulator.
- "Pred in" was a 0.0004in total trimmed to nothing → now "<.001in". Same function also turned 20mm
  into "2mm" for metric users — fixed.
- Desktop pill checked on screen and by click.
- 5610 unit tests + 26 localization tests pass.
