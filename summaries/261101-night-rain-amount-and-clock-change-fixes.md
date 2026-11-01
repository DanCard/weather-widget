# Night rain amount label and clock-change fixes

Date: 2026-11-01

## User prompts

1. Fold-4: instead of listing 100% night time rain chance, can we get forecasted amount of rainfall
   instead?
2. go ahead with those thresholds.
3. Test failed, can you look at staggered tests log
4. commit
5. fix time zone test failures
6. Fix other callers. Add a test for spring-forward.
7. write recap to summaries/

## Night rain amount (committed: `b159f610`)

**Evidence.** Fold logcat showed the "100%" was `date=2026-10-31`, the night in progress at 02:40 on
Nov 1. After midnight that night's date counts as past, so it took the history branch of
`buildNightRainLabel`, which could only print a measured amount or a chance. All seven sources store
hourly `precipAmountMm`; only 4 of 305 hours at ≥ 50 % had 0 mm (all OWM).

**Change.**

1. `DailyRainLabels.nightForecastAmountMm` sums the displayed source's hourly amounts at the site over
   8pm–8am. It is null unless the whole night is covered with no hole and every hour has an amount.
   No GENERIC_GAP fallback and no stored column: nights past the stored hours keep the %.
2. `buildNightRainLabel` shows the amount at ≥ 95 % tonight or for the night under way
   (`isNightInProgress`), and at ≥ 99 % for later nights. A zero or null total keeps the %.
3. Finished nights keep measured amount, else chance. A forecast amount there would read as rain that
   fell.
4. While a night is under way, the forecast total beats the measured amount, which only holds the
   hours so far.

**Verified on the Fold** (Sat→Today gap): OWM 100 % → `.14in`, Tomorrow.io 97 % → `.11in`, Silurian
stays `80%`. The labels fit; `.11in` sits low, close to the "Today" day name. Desktop showed 92 % for
that night, so its amount was not seen drawn; it uses the same `DailyColumnBuilder` path.

## Clock-change failures (uncommitted when this was written)

The staggered run at 02:50 had 2 desktop failures, both also failing with the rain change stashed.
`user.timezone` is pinned to `America/Los_Angeles` in the Gradle test config, so TZ overrides have no
effect: a Tokyo rerun proved nothing.

1. `DeferredObservationWindowTest`: `now.minusDays(3)` keeps the wall clock, which is 73 h across the
   fall-back change, and the test assumed 72 h. Test bug, fixed in `b159f610`. It would have blocked
   `--install` until about Nov 4.
2. `DesktopWeatherRepositoryTest` `loadCached resolves correct temperature delta`: failed only from
   02:00 to 03:00 and passed on the 03:00 rerun. This one was a real `:shared` bug.

**Root cause.** `CurrentTemperatureResolver` went from instant to `LocalDateTime` and back, and `atZone`
picks the first of the two repeated hours. So 01:30 PST read as 01:30 PDT, an hour early. That hit the
observation estimate, the forecast-run bracket and `now`, on Android and desktop.

**Fix.**

1. The resolver's hour lookups run on instants; the next hour is exactly `+ HOUR_MS`.
2. `resolve`, `resolveQuick` and `CurrentTempRun.header` take an optional `nowMs`.
3. `WeatherTimeUtils.liveEpochMs` resolves a wall-clock now to the offset nearest the real clock. This
   avoids threading an instant through about 39 `LocalDateTime.now()` sites. It is only valid for a
   time read from the clock.
4. `WeatherTimeUtils.hourStartMs` keeps the offset when flooring to the hour.
5. `CurrentTemperatureResolver.resolutionWindowMs` is one exact window (12 h back, 3 h ahead) replacing
   wall-clock arithmetic in the resolver, `CurrentTempRun`, `GraphDataLoader`, `DailyGraphRenderer`,
   `DominantTempChangeNotifier` and desktop. The old window came out 14 h on spring-forward day.

**Tests.** `CurrentTemperatureResolverClockChangeTest` has 13 tests pinned to instants on 2026-11-01
and 2027-03-14, so they run any day. Restoring the old conversion failed 2 of the first 3 tests in
`hourStartMs`, and 1 test in `liveEpochMs`. The spring-forward estimate and delta tests lock in
behavior the old code probably already had; the real spring-forward defect was the window.

Final gate: 6,501 JVM tests and 109 of 109 on both emulators. The clock-change paths were not seen
live, since the repeated hour had passed.

## Open items

1. Observed `precipNightMm` is bucketed by calendar date, not 8pm–8am. KNUQ's 1.0 mm at 01:55 Nov 1
   sits on Nov 1's row and Oct 31's night row stays 0, so that night shows a % instead of the rain.
2. Comments in `DominantTempChangeNotifier.kt:52`, `DesktopWeatherRepository.kt:156` and
   `CurrentTempRun.kt:23` still name `buildCurrentTempResolutionWindow`, which `resolve` now uses only
   for a log line.
3. The Pixel has neither change. The Fold and desktop have both.
4. A `BUILD SUCCESSFUL` last line can hide desktop failures: read the "tests, N failed" line or the
   `Done: unit=` result.
