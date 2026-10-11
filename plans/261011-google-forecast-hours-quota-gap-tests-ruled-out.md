# Google forecast/hours quota gap: the tests are ruled out

No code change. A record of the investigation, so the next quota-gap hunt starts past this point.

## Symptom (2026-10-11)

Desktop hit Google's `forecast/hours` daily quota at 12:20 PDT:
- 429 `quota_limit_value: "100"`, `window_start_time` = 00:00 PDT.
- The limit was raised to 120 the same day; a 14:12 emulator boot got 200s.

`scripts/google_usage_today.py` totals 58 for the day, with both emulators attached. Fewer than
50 of those were logged before the 429, so about 50 requests Google counted left no record on any
device.

## Hypothesis tested: the instrumented suite spends the key out of sight

Why it looked likely:
- Both emulators ran the suite that morning (test prefs written 08:07 and 08:38).
- The test process runs the real app with test prefs (`*_test_default`), so a debug build puts
  Google first, and the tests leave Austin as the location.
- It writes to `weather_database_test_default`. `AndroidTestDatabase.clearAllTables()` empties that
  DB around every test, so any `GOOGLE_REQUEST` line logged there would be erased, and the usage
  script reads only `weather_database`.

Why it is wrong:
- `WeatherWidgetWorker.doWork` and `HourlyOnDemandWorker` drop all work while
  `WeatherDatabase.isTestingMode()`.
- Work a test queued that comes due later in a normal process is dropped by
  `KEY_ENQUEUED_IN_TESTING` (logged `SYNC_SKIP_TEST_ENQUEUED`).
- Every forecast fetch reaches the network through one of those two workers. The only callers
  outside them are `ForecastHistoryActivity` and `WeatherObservationsActivity`, which no test opens.
- JVM tests (`:app`, `:shared`, `:desktop`) build Google clients on `MockEngine` only.
- Confirmed: `LocationUpdaterIntegrationTest`, which queues a forced Austin refresh, ran on
  emulator-5554 with logcat captured. It produced zero `GOOGLE_REQUEST` lines and two
  `SYNC_SKIP_TEST_ENQUEUED` drops. A test-mode key guard was written, found redundant, and reverted.

## Still unexplained

About 50 requests Google counted before 12:20. Not yet checked:
- Google Cloud console → Weather API → Metrics, split by credential.
- Another machine or device running a debug build or the desktop app with the same key.
- Desktop `:desktop:run` dev instances.
