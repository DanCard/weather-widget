# WeatherNext viewer dates follow the locale

Plan: `plans/261020-weathernext-localized-date-pattern.md`.

- Fixed English `EEE MMM d, h:mm a z` → each locale's own full-date order without year + short time;
  device 12/24 h on Android; zone dropped.
- Russian/Ukrainian year suffix survived the first try (CLDR no-break space) — caught by the
  20-locale test.
- Verified in German on the emulator; 5611 tests pass.
