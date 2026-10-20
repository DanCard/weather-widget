# WeatherNext viewer: localized date/time instead of a fixed English pattern

`WeatherNextForecastClient.formatUtcToLocal` used `EEE MMM d, h:mm a z` for every locale, so the
translated viewer (`plans/261020-sim-loop-availability-viewer-i18n-precip-amount-format.md`) read
"Di. Okt. 20, 8:00 AM GMT-07:00" in German.

## Change
- Pattern = the locale's CLDR FULL date pattern minus its year (", y", " 'de' y", " y 'г'.",
  "y年", "G y", "، y"), EEEE→EEE, MMMM→MMM, + the locale's SHORT time; zone dropped (always device
  time). No-break spaces in CLDR patterns are matched explicitly (`\s` misses them — left "г.").
- 12/24 h: Android passes `DateFormat.is24HourFormat`; desktop uses the locale
  (`DailyRainLabels.localeUses24HourClock`), as the peak-hour label does.
- Formatters cached per (locale, clock): the viewer formats every 35 ms tick.
- JVM has no skeleton API (Android's `getBestDateTimePattern`), hence deriving from FULL in `:shared`.

## Tests
`WeatherNextTest`: en-US exact; all 20 app locales — no year / year suffix, no zone, 24 h "20:00",
12 h "8:00", day of month present.

## Verification (2026-10-20)
- 5611 unit tests pass. Emulator, `de-DE` per-app locale: "Modelllauf: Di., 20. Okt., 08:00",
  "Gültig: Mi., 21. Okt., 21:00". Desktop rebuilt and restarted.
