# Google daily low from its own hourly morning

Plan: plans/261021-google-daily-low-from-hourly-morning.md

- Bug (emulator, Google, Wed 10/21): daily low 51°, hourly graph 57.5° (stations 57.4°).
- Cause: Google's day minimum covers 07:00 → 07:00, including its own 07:00. After a cold morning and a
  mild night that minimum is the previous morning; the 261010 shift filed it under the next day.
  2 of 14 stored days (10/16, 10/21).
- Fix (`:shared` `GoogleWeatherApi.lowsFiledUnderTheirMorning`, both platforms): a day whose
  00:00–09:00 is fully in the fetch's **forecast** hours takes its low from them; otherwise the shifted
  day minimum, as before. Logs `GOOGLE_DAILY_LOW from_hourly=N shifted=N`.
- Elapsed `history/hours` are excluded: they would make today's forecast low an after-the-fact value.
- Not repaired: stored rows. Today (10/21) keeps 51° until tomorrow.
- Tests: 5 in `GoogleWeatherApiTest`; integration case in `GoogleWeatherFetchIntegrationTest`
  (fixture engine gained `daysTransform`). Suites: shared 2,390 · desktop 641 · app 2,629, 0 failed.
- Emulator: Oct 22–28 lows = rounded hourly morning minimum; desktop restarted.
