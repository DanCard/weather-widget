# Plan: audit the test suite so every test is helpful

User (2026-11-12): "Can you review the testing strategy? ... Would it be a good idea to audit tests and
make sure each test is helpful?" Then: "make the doc changes and write the audit plan".

The bar is in `arch/testing-strategy.md`, "What makes a test worth keeping" (added with this plan): a test
fails on wrong code, asserts an outcome, names one behavior, and is not a copy.

## Evidence (measured 2026-11-12, commit a90f422c1)

| Source set | Files | `@Test` | Notes |
|---|---|---|---|
| `app/src/test` | 455 | 2,646 | 292 files use Robolectric; 264 of 448 categorized classes are Long |
| `shared/src/test` | 386 | 3,179 | no mocking framework; 6 non-Short classes |
| `desktop/src/test` | 176 | 789 | |
| `app/src/androidTest` | 44 | 125 | |

- No coverage or mutation tooling in any build file. Nothing measures whether tests detect wrong code.
- MockK: 136 app test files and 29 desktop files; 531 `relaxed = true` mocks; 35 files use
  `mockkStatic`/`mockkObject`/`mockkConstructor`. Heaviest: `WeatherRepositoryTest` (26 relaxed),
  `DailyBackfillProbeCostTest` (19), `WeatherRepositoryNwsParallelTest` (18), `WeatherGapTest` (18).
  The strategy doc says MockK is for boundaries where a real or fake collaborator is not practical.
- 61 `*Test.kt` files contain no assertion or verify call. 52 are contract subclasses (fine) and 2 are
  base classes. To read: `SunPositionDiagnosticTest`, `ForecastFetchCoordinatorFollowUpsTest`,
  `ForecastFetchCoordinatorCoverageTest`, `WidgetRefreshCoordinatorTest`, `WeatherRepositoryNwsParallelTest`,
  `ObservationFetchReplayHarnessTest`, `DesktopRefreshFollowUpsTest`. Some may assert through helpers.
- 49 `Thread.sleep` calls in tests, mostly desktop (`DesktopStartupTest` 6, `HistoryRefreshOutcomeTest` 4).
  Git history has repeated desktop flake fixes (`d3d1936fb`, `42099ca7e`, `f5f4902cf`).
- 29 test files read `src/main` text (guard tests). Not checked: whether each has a behavioral partner.
- Passing gate logs contain many `E/TOGGLE_API_FAIL ... NullPointerException: Cannot read field "layoutId"
  because "widgetInfo" is null` lines (also `CYCLE_ZOOM_FAIL`, `RESET_SOURCE_FAIL`, `TOGGLE_VIEW_FAIL`).
  Not yet known: whether those tests exercise the error path on purpose or pass despite a swallowed NPE.
- 583 of the last 742 commits (90 days) touched tests. Largest files: `DailyViewLogicTest` (2,410 lines),
  `DailyViewHandlerTest` (1,896), `DailyForecastGraphRendererRoboTest` (1,681).

## Approach

Find unhelpful tests by signal, not by reading 6,800 tests. Each phase ends with a findings list in this
file and a user decision before any test is deleted or rewritten. No production behavior changes, except
a real bug a phase uncovers, which gets its own plan.

### Phase 1: mutation testing on `:shared`
1. Add Pitest to `:shared` as a manual task only (not in `unit-tests.sh` or the gate). Check current
   Kotlin/JUnit4 plugin support before choosing the plugin version.
2. First run on one package (`shared/graph` or the rain label layout) to size run time, then the module.
3. Report per package: mutation score, surviving mutants, and tests that kill no mutant.
4. Triage survivors: add a discriminating test, or record the mutant as equivalent.

Why first: `:shared` holds the rules, has no Android dependency, and mutation score is the direct measure
of "this test would fail on wrong code".

### Phase 2: the `widgetInfo is null` error lines
1. Find which tests emit them and whether each asserts the outcome of the action it triggers.
2. If a handler swallows the NPE and the test still passes, fix the fixture (bind widget info) and make
   the test assert the result. Small; do before Phase 3 because it may indicate a class of hollow tests.

### Phase 3: relaxed mocks and assertion-free tests in `:app` and `:desktop`
1. Read the seven assertion-free files above.
2. For the 20 files with the most relaxed mocks, classify each test: asserts a real outcome / verifies
   only mock calls / mock stands in for the thing under test.
3. Propose per file: keep, replace mock with a fake or in-memory Room, extract a pure function, or delete.
4. Decide with the user whether the MockK rule in the strategy doc should be tightened or reworded to
   match practice.

### Phase 4: sleeps and guard tests
1. Classify the 49 sleeps: wall-clock is the subject (keep, comment) or waiting for work (replace with
   virtual time or a latch).
2. For each of the 29 guard tests, name its behavioral partner test or flag that none exists.

### Phase 5: layer and duplication
1. List Robolectric classes whose code under test has no Android dependency; propose moving the logic
   test to pure JVM (or to `:shared` where the rule lives there).
2. List app or desktop tests that restate a rule already tested in `:shared`; propose removal.
3. Split the three largest test files only if a phase above shows a concrete problem in them.

## Out of scope
- Instrumented tests (125, each reviewed when written, and the only proof of real rendering).
- Adding CI or a coverage percentage target.
- Any test deletion without the covering test named, per the strategy doc.

## Verification
- Each phase: `./scripts/unit-tests.sh --fresh` passes; Phase 2 and any `:app` fixture change also run
  `./scripts/staggered-tests.sh`.
- Phase 1: mutation score before and after the added tests, per package.
- Phases 3 to 5: test count, Long-class count, relaxed-mock count and sleep count before and after.
- A rewritten test is shown to fail against a deliberately broken copy of the code it covers.

## Phase 1 result (2026-11-12): mutation testing on `:shared`

Setup: `info.solidsoft.pitest` 1.19.0 (PIT 1.22.1) applied to `:shared` only, as the manual task
`./gradlew :shared:pitest` (`-PpitestTarget='<package>.*'` for one package). Works on Gradle 9.4 with
the configuration cache on. Liveness tests excluded. `scripts/pitest_summary.py` summarizes the report
and counts Kotlin accessor/data-class mutants separately from rule mutants.

Whole module: 3 min 54 s, 22,223 mutants, 86% line coverage of mutated classes.

| | Rule mutants | Killed | Survived | No coverage | Score | Test strength |
|---|---|---|---|---|---|---|
| All of `:shared` | 18,195 | 11,172 | 4,074 | 2,921 | 61% | 73% |
| `shared.graph` | 5,083 | 3,005 | 1,597 | 481 | 59% | 65% |
| `data.remote` | 2,878 | 1,623 | 544 | 711 | 56% | 75% |
| `shared.util` | 2,875 | 2,141 | 436 | 298 | 74% | 83% |
| `shared.actuals` | 1,696 | 1,024 | 319 | 353 | 60% | 76% |
| `shared.observations` | 1,401 | 896 | 221 | 284 | 64% | 80% |
| `data.local.desktop` | 952 | 337 | 259 | 356 | 35% | 57% |

Score = killed / all; test strength = killed / covered. 4,028 accessor mutants are excluded above.

Reading the numbers:
- The score is a floor. Pitest runs only `:shared`'s tests; app and desktop tests also exercise this code.
- **Covered but not checked** (low strength; the tests run the code and do not notice it change):
  `ValueLabelEngine` 29% (155 survivors, mostly negated conditionals in `place`, `selectCandidates`,
  `softDipCandidates`; one `:shared` test file, none in app or desktop by name), `DesktopWeatherDatabase`
  33%, `OpenMeteoApi` 39%, `LabelSuppression` 43%, `ActualExtremePlacers` 49%, `TemperatureExtrema` 56%
  (164 survivors), `LabelCandidateCollector` 58% (146 survivors).
- **Not run by any `:shared` test** (0% coverage): `OpenWeatherMapApi` (149 mutants; three app test files
  reference it), `NwsForecastReply` (103; no test in any module names it).
- **Tested mostly from another module**: `DesktopWeatherDao` lives in `:shared` (3,022 lines) with 284
  uncovered mutants; 77 desktop test files reference it against 16 in `:shared`.
- 307 mutants timed out (counted as killed, the Pitest convention).

Not done: the "tests that kill no mutant" list. It needs Pitest's full mutation matrix, which runs every
covering test against every mutant; run it per package once the packages to work on are chosen.

Next (needs a user decision): which classes to triage first. Proposed order: `ValueLabelEngine`,
`TemperatureExtrema`, `LabelCandidateCollector`, `LabelSuppression` (label placement, the area with the
most reported bugs), then `NwsForecastReply` and `OpenWeatherMapApi` parsing.

### Phase 1 triage: `ValueLabelEngine` (2026-11-12)

| | Rule mutants | Killed | Survived | Test strength |
|---|---|---|---|---|
| Before (8 tests) | 239 | 63 | 155 (+21 uncovered) | 29% |
| After (39 tests) | 239 | 198 | 41 | 83% |

- Added 31 exact-outcome tests to `ValueLabelEngineTest`. Each pins the full result as
  `index:text:A|B:reason`. Expected values were derived from the rules before running; 30 of 31 matched
  the code, and the one miss was a wrong derivation (a label with no room in either direction is dropped).
  No engine behavior was changed.
- The 8 original tests are kept. Their assertions are loose ("not null", "no overlap", "size <= max"),
  which is why they killed 63 of 239 mutants while executing nearly every line.

Remaining 41 survivors:
- **13 in `zeroRunMidpoints` plus line 218: dead code.** Zero-run midpoints are added to
  `protectedIndices` but never to `candidates`. With `requireNonZeroExtrema = true` (precip) a zero-valued
  midpoint can never be a candidate, and cloud sets `includeZeroRunMidpoints = false`. So a dry gap
  between two rain spells never gets its own label, and the option changes nothing in either shipping
  config. The pre-extraction Android renderer (`eff873550`) had the same shape, so this is not a
  regression from the move to `:shared`. Needs a user decision: remove the code, or make the midpoint a
  real candidate (a behavior change, own plan).
- **8 on lines 223 to 225: equivalent.** The guards repeat what later steps already ensure (index 0 is
  always added, duplicates are removed by the dense filter, a local maximum of a non-negative signal is
  never 0).
- **9 conditional-boundary mutants** (soft-dip window edges, right-edge and trend thresholds, the
  out-of-range index guard): killable with more boundary cases; low value each.
- **The rest:** a removed `Log.d`, a `return emptyList()` replaced by the same value, the
  `firstLabeledPositive` protection (same rule as the tested `firstPositive` one), one gap-selection
  branch where both gaps are 2 dp, and Kotlin-generated default-argument and comparator code.

User (2026-11-12): "remove dead code, proceed".

- Removed `zeroRunMidpoints`, `Config.includeZeroRunMidpoints` and the protected-index entry from
  `ValueLabelEngine` (24 lines). No caller set the option. `ValueLabelEngine` is now 189 of 216 rule
  mutants killed, 88% test strength.

### Phase 1 triage: `TemperatureExtrema` (2026-11-12)

| | Rule mutants | Killed | Survived | Test strength |
|---|---|---|---|---|
| Before | 374 | 208 | 164 (+2 uncovered) | 56% |
| After | 374 | 284 | 89 (+1 uncovered) | 76% |

- New file `TemperatureExtremaRulesTest` (36 tests): local extrema and plateaus, prominence walk,
  whole-window and forecast-side extrema, the now boundary, the five-degree "today's high reached"
  margin, past-forecast turn confirmation, the 0.75 degree reversal gate, and per-day highs and lows
  (inter-peak trough, three-degree prominence, shoulder drop, noise-floor spread).
- Every expectation was derived from the rule first; all 36 matched the code. No behavior changed and no
  defect found in this class.
- Of the 89 survivors, about 50 are in `Log.v` message construction (`PERDAY_RAW`, `ACTUAL_EXTREMA`,
  `FORECAST_EXTREMA` and similar) or Kotlin-generated code. The rest are conditional-boundary mutants on
  loop and range bounds, most of them equivalent (for example the absolute maximum's neighbours can never
  be greater, so the direction check in `turnsInsidePastSegment` cannot be told apart from `true`).

Full gate after the dead-code removal (2026-11-12 23:21): 6,860 unit tests and 125 instrumented tests
passed (`logs/staggered-tests/summary-20261112-231916.log`).

### Phase 1 triage: `LabelCandidateCollector` (2026-11-12)

| | Rule mutants | Killed | Survived | Test strength |
|---|---|---|---|---|
| Before | 353 | 201 | 146 (+6 uncovered) | 58% |
| After | 353 | 205 | 143 (+5 uncovered) | 59% |

- New file `LabelCandidateCollectorRulesTest` (9 tests) calls `collect` directly and pins the full
  candidate list for a forecast-only window, the end-label redundancy window, a now boundary, coincident
  forecast and observed highs, the fallback observed turns, and the forecast midpoint.
- They killed only 4 more mutants. Whole-result scenarios overlap what the existing resolver-level
  tests (`TemperatureLabelSuppressionTest` and others) already detect.
- Of the 148 remaining: about 49 are `Log.v` / `logLabelDecision` / provenance strings or
  Kotlin-generated code, and 54 are conditional-boundary mutants, mostly on defensive guards
  (`index >= 0`, `idx in temps.indices`) that no valid input can reach. The anchor de-duplication
  priority check also survives negation: a plateau test showed the same final labels either way,
  because the later role resolution reaches the same answer.
- Conclusion: this class is near what output-level tests can detect. Further gains need tests of the
  center label and pixel-window paths (`widthPx > 0`), not more scenarios of the kind added here.

### Status after three classes

| Class | Before | After | New tests |
|---|---|---|---|
| `ValueLabelEngine` | 29% | 88% | 31 |
| `TemperatureExtrema` | 56% | 76% | 36 |
| `LabelCandidateCollector` | 58% | 58% | 0 (9 written, then dropped) |

Not started: `LabelSuppression`, `NwsForecastReply`, `OpenWeatherMapApi`.

User (2026-11-12): "commit. Drop 9 collector tests. proceed". `LabelCandidateCollectorRulesTest` was
deleted before the commit: its tests added four mutant kills over the existing resolver-level tests.
Next: the two parsers with no `:shared` test, `NwsForecastReply` and `OpenWeatherMapApi`.

### Phase 1 triage: the two parsers (2026-11-12, uncommitted)

- `NwsForecastReply`: new `NwsForecastReplyTest` (8 tests) against a recorded reply. 0% to 77% test
  strength (47 of 103 killed). The rest is coroutine plumbing and `logPeriodSummary`'s PAST/ACTIVE/FUTURE
  marker, which reads `ZonedDateTime.now()` and so cannot be pinned.
- `OpenWeatherMapApi`: `OpenWeatherMapApiTest` moved from `:app` to `:shared` (it has no Android
  dependency) and extended from 3 to 8 tests: interpolation, visibility, local-date grouping, the 1 pm
  representative, missing key, request parameters. 0% to 89% test strength (125 of 149 killed);
  the survivors are coroutine and ktor request plumbing.
- **Defect found, not fixed:** `OpenWeatherMapApi.kt:103` converts rain chance with
  `(pop * 100).toInt()`, which truncates. A probe test showed 0.53 stored as 52 and 0.59 as 58. It is
  the only such conversion in main code. Fix is `roundToInt()`; needs user approval.
- Also noted, not pinned: a period with no temperature is stored as 0 °F (`?: 0f`, line 99).
- Other `:app` tests of `:shared` API clients remain in `app/src/test/.../data/remote/`
  (`NwsApiTest`, `OpenMeteoApiTest`, `SilurianApiTest`, `TomorrowIoApiTest`, `WeatherApiTest`, ...);
  whether they can move like this one is unchecked.

User (2026-11-13): "fix truncation, commit, move the other API tests. Fix rain truncation. Fix no temp
stored as zero."

- `OpenWeatherMapApi`: rain chance now uses `roundToInt()`; a period with no temperature is dropped
  (logged `OWM_PERIOD_NO_TEMP`) instead of stored as 0 °F. Before the fix the interpolation drew a slope
  through the 0 °F row (60, 40, 20, 0, ...). Both new tests fail on the old code.
- Full gate 2026-11-13 06:17: 6,875 unit tests and 125 instrumented tests passed.

### API client tests moved to `:shared` (2026-11-13, uncommitted)

- Moved from `app/src/test/.../data/remote/` to `shared/src/test/.../data/remote/`:
  `NwsApiStationInfoCodecTest`, `NwsApiTest`, `NwsPrecisionTest`, `OpenMeteoApiTest`, `SilurianApiTest`,
  `TomorrowIoApiTest`. None had an Android dependency. `:shared` gained `kotlinx-coroutines-test` for
  their `runTest`. `NwsPrecisionTest` and `OpenMeteoApiTest` measured under 0.2 s and are now Short.
- `WeatherApiTest` stays in `:app`: it mocks the app's `WidgetStateManager`.
- Unit suite total unchanged at 6,875.

Whole-module mutation run after all Phase 1 work so far:

| | Rule mutants | Killed | Score | Test strength |
|---|---|---|---|---|
| Start of Phase 1 | 18,195 | 11,172 | 61% | 73% |
| Now | 18,173 | 11,751 | 65% | 75% |

`data.remote` went from 56% to 68% score. Per class: `NwsApi` 91% strength, `OpenWeatherMapApi` 89%,
`TomorrowIoApi` 86%, `NwsForecastReply` 77%, `OpenMeteoApi` 72%, `SilurianApi` 64%.

## Phase 2 result (2026-11-13, uncommitted): the `widgetInfo is null` errors

Cause: Robolectric's `ShadowAppWidgetManager.updateAppWidget` throws `NullPointerException` for a widget
id it did not create. Nine Robolectric classes used made-up ids (42, 55, 77, 123, 9112, 99991, 99992).
`WidgetInteractionCoordinator.runInteraction` logs the failure and swallows it, so the handler stopped
at its first push (`WidgetPushDispatcher.push`) and the tests still passed: they assert state and
enqueued work that happen before the push. Nothing after the push ran. The comment in
`DailyViewApiToggleIntegrationRoboTest` records that this already hid one ordering bug.

Changes (tests only):
- New `app/src/test/java/com/weatherwidget/test/BoundTestWidget.kt`: `create()` makes a widget the
  shadow knows, and `assertNoInteractionFailures()` fails a test if `runInteraction` swallowed anything.
- Eight classes now use a bound id and check for swallowed failures in `@After`: `ZoomCycleRoboTest`,
  `WidgetRefreshToastIntegrationRoboTest`, `DailyViewApiToggleIntegrationRoboTest`,
  `CloudCoverViewModeRoboTest`, `SourceViewRecordingRoboTest`, `WeatherWidgetProviderNoHourlyRoboTest`,
  `TemperatureActualsIntegrationTest`, `NavigationPersistenceRoboTest`.
- 37 empty `try { ... } catch (_: Exception) {}` wrappers removed from those classes and from
  `WidgetIntentRouterCrashSafetyRoboTest`. That class keeps its invalid id on purpose; without the
  wrappers its "does not crash" tests can now fail if a handler throws.
- All pass with the paint path running and no swallowed failure; no defect surfaced. Unit suite 6,875.
- Fresh results contain the exception only in `WidgetIntentRouterCrashSafetyRoboTest`, by design.

Not done: a general guard. Any other Robolectric test that calls a handler with an unbound id will
still pass silently; `BoundTestWidget` is opt-in.

## Phase 3 result (2026-11-13): assertion-free and relaxed-mock tests

Correction to the evidence section: the "61 files with no assertion" count used a case-sensitive
search and missed `coVerify`. Counted correctly, only two test files cannot fail.

1. **Cannot fail (2 files):**
   - `app/.../util/SunPositionDiagnosticTest.kt`: prints day/night for 20 times and asserts nothing.
     `SunPositionUtilsTest` covers the same function with assertions.
   - `desktop/.../ObservationFetchReplayHarnessTest.kt`: a one-off record/replay tool for
     `plans/261013-shared-current-conditions-fetch.md`, skipped unless `REPLAY_DIR` is set.
   Proposed: delete both (needs user approval, per the strategy doc).
2. **Relaxed mocks (20 heaviest files, 111 tests):** 95 assert a returned or stored value, 16 verify
   only mock calls, none check nothing. The relaxed mocks are constructor collaborators the test does
   not care about; the 16 verify-only tests check routing decisions whose outcome is a call (which
   source is fetched, which follow-up runs). No hollow test found by this classification.
3. **Absence-only tests (21, `verify(exactly = 0)` or `wasNot Called` with no other check):** every
   class that has them also has presence cases. One is weak: `WeatherRepositoryTest`
   "refreshCurrentTemperature never writes the historical POI list" runs with Silurian only, which
   fetches no stations, so no code path that could write the list is reached.
4. **Static and object mocks (35 files):** every `mockkObject`/`mockkStatic`/`mockkConstructor` is
   undone in the same file. No leak between tests.
5. **MockK rule:** practice is "relaxed mocks for collaborators the test ignores, real or in-memory
   objects for the thing under test". The strategy doc's "only where extraction or a real collaborator
   is not practical" is stricter than that. Proposed: reword the doc to match practice.

Limit: this phase classified tests by what they check, not by whether the check would catch a wrong
change. `:app` has no mutation testing, so a test that asserts a value a mock was told to return is
not detected here.

User (2026-11-13): "Yes delete files that cannot fail. Yes reword. Yes fix weak tests."

- Deleted `SunPositionDiagnosticTest` and `ObservationFetchReplayHarnessTest` (covered by
  `SunPositionUtilsTest`; the replay harness was a finished one-off tool).
- `arch/testing-strategy.md`: MockK rule reworded to practice (relaxed mocks for ignored
  collaborators, the thing under test is real, pair every "never called" check with a reachable
  presence case, undo static mocks).
- `WeatherRepositoryTest` POI test now runs with NWS and asserts a feed was fetched (result 1) before
  checking the list was not written.
- `OpenWeatherMapApi`: the two interpolated hours' rain chance and cloud now use `roundToInt()` too
  (a side review found they still truncated: a third of the way from 0% to 20% was stored as 6%, not
  7%). The interpolation test fails on the old code.
- Unit suite: 6,873 (two tests deleted). Emulator gate not re-run. Desktop not restarted: an emulator
  was running, and the 06:24 GPU fault happened on a desktop restart with an emulator up.

## Phase 4a result (2026-11-13, uncommitted): the `Thread.sleep` calls

49 matches: one is a comment, 48 are real.

| Kind | Count | Verdict |
|---|---|---|
| Poll interval inside a loop with a deadline | 20 | Fine: the wait ends on a condition |
| Two rows need distinct millisecond timestamps | 17 | Fine: wall-clock time is part of the key or order |
| Retry pause in a liveness test | 2 | Fine |
| Elapsed time is the subject (held lock, debounce, "nothing arrives") | 6 | Fine: `DesktopWriteTimingTest`, `DesktopAppLogQueueTest`, `RainForecastLayerSwitchTest`, `UiNotifyChannelTest`, `ResizeDebounceInstrumentedTest` (2) |
| Fixed wait for background work | 3 | Fixed, below |

- `DesktopStartupTest` (2 sleeps of 1.5 s): guessed when the daemon's WatchService was registered.
  The daemon logs "Starting headless WeatherDaemon" before registering, and a trigger file written
  before registration is lost. The tests now re-send the trigger every 500 ms until the daemon reacts.
  Class time 2.3 s (the two sleeps alone were 3 s); three runs passed.
- `SettingsSourceListRoboTest` (1 sleep of 200 ms before reading log rows): the presence check now
  polls until the expected rows exist; the "nothing logged" check keeps the fixed wait.
- Several of the 17 timestamp sleeps carry no comment saying why (the strategy doc asks for one). Not
  counted or changed.
- Product observation, not changed: a `.show` or `.refresh-requested` trigger written in the window
  between daemon start and WatchService registration is lost (`DaemonRuntime.kt:302-323`).

User (2026-11-13): "commit. Work on product observations, and 17 timestamp sleeps".

### Lost startup triggers (desktop daemon, uncommitted)

- Cause: a WatchService reports only changes made after it is registered, and the daemon registers it
  some time after the process starts. A `.show`, `.refresh-requested` or `.config-changed` written in
  that window was never seen. `scripts/buildStart-desktop.sh` already worked around it by re-touching
  `.show` for up to 30 s ("startup can take ~10 s").
- Fix: `pendingStartupTriggers(appDir, processStartMs)` in `DesktopProcess.kt` lists the consumed
  triggers last modified at or after this process's OS start time. `DaemonRuntime` handles them right
  after registering the watch, through the same `handleTrigger` the event loop now uses. A
  `.refresh-requested` older than the process is still deleted as a leftover; a newer one is kept.
- Tests: `PendingStartupTriggersTest` (4 cases, explicit file times) and `DesktopStartupTest`
  "a show trigger written once as the daemon starts is not lost". The latter failed 3 of 3 runs on the
  old daemon code and passed 3 of 3 on the fix.
- The build script's re-touch loop is left in place; it is now redundant but harmless.

### The 17 timestamp sleeps (uncommitted)

| File | Sleeps | Change |
|---|---|---|
| `DesktopWeatherDaoTest` | 2 | Explicit `timestampMs` on each row |
| `HistoryRefreshOutcomeTest` | 4 | Explicit `timestampMs` |
| `DesktopProductQuotaTest` | 1 | Explicit `timestampMs` |
| `AppLogsContractTest` | 1 | Explicit `timestampMs` |
| `DesktopForecastHorizonStorageTest` | 3 | Removed: the writes already pass explicit times |
| `DesktopSnapshotDisplayedRainChanceTest` | 2 | Removed: the writes already pass explicit times |
| `DesktopSourceRetryHostContractTest` | 2 | Kept: the host writes with the real clock; already commented |
| `ForecastSnapshotHindcastCutoffTest` | 2 | Kept: `fetchedAt` is the real clock and part of the key; second one now commented |

13 of 17 removed. The four desktop classes ran 8 times in a row (33 tests each) with no failure.

## Phase 4b result (2026-11-13, uncommitted): guard tests and their partners

30 test files read `src/main` (29 on 2026-11-12; `ButtonPressTokenParityTest` is new). No test changed.

**Not source-text guards (11 files).** They compare real data or load real resources, so they are
their own proof: `ButtonPressTokenParityTest`, `RainLoopErrorLineParityTest`,
`SourceErrorDetailsEnglishParityTest`, `StationsListEnglishParityTest`, `LocaleResourceParityTest`,
`LocaleStringFormattingRobolectricTest`, `DesktopPainterResourceTest`, `HostContractCoverageTest`
(requires a behavioral suite per port), and the three whose rule is itself structural
(`HardcodedUserFacingStringTest`, `CrossPlatformDuplicateNamesTest`, `CrossPlatformDuplicateLogTagsTest`).

**Call-site guards (10 files): each has a partner.**

| Guard | Behavioral partner |
|---|---|
| `HourlyProximityQueryAllowlistTest` | `GraphDataLoaderUnifyToNearestSiteTest`, `DailyCloudCoverSiteParityRoboTest` |
| `HourlyCollapseChokepointTest` | `HourlyForecastStitcherTest`, `HourlyStaleFragmentCollapseTest` |
| `ObservationProximityQueryAllowlistTest` | `ObservationSiteMergeTest`, `ObservationReadAcrossLocationMoveTest` |
| `MigrationSqliteCompatGuardTest` | `WeatherDatabaseMigrationTest` (instrumented) |
| `UserRefreshScopeGuardTest` (app, desktop) | `UserRefreshRunnerTest` (`:shared`), `WeatherObservationsRefreshOutlivesScreenIntegrationTest`, `DaemonFetchTest` |
| `CurrentConditionsCallSiteGuardTest` (app, desktop) | `CurrentConditionsFetcherTest`, both `*CurrentConditionsHostContractTest` |
| `ForecastDeltaCallSiteGuardTest` (app, desktop) | `ForecastDeltaViewsIntegrationRoboTest`, `DesktopForecastDeltaViewsIntegrationTest` |

Can each scan still match a violation? The three `:shared` scanners (`UserRefreshCallSites`,
`CurrentConditionsCallSites`, `ForecastDeltaCallSites`) have their own tests. The other four carry an
inline regex with no self-test, so a temporary file with one violation of each was added to
`app/src/main`: all four failed, and the file was removed. Each also guards against a vacuous pass
(stale-allowlist check, or an assertion that the expected file was scanned).

**Source assertions inside behavioral classes (6 files): partner is in the same class or beside it.**
`DesktopHeaderDateTest` (+ `DesktopHeaderDateIntegrationTest`), `DesktopHousekeepingLoopTest`
(+ `DesktopHousekeepingGateTest`), `DesktopSnapshotPublishTest`,
`DesktopWindowBringToFrontIntegrationTest`, `RefreshNoticeStateTest`, and the two source scans in
`WidgetActivityPendingIntentFlagsGuardTest` (they check its pinned list is complete).

**Structure-only tests with no behavior to pair (3 files).** `DailyForecastGraphRendererArchitectureTest`,
`HeaderPrecipitationArchitectureTest`, `DesktopUiArchitectureTest` pin where code lives. Weak points,
not changed:
- Their "must not contain `fun X(`" checks only fail if someone re-adds that exact name.
  `HeaderPrecipitationArchitectureTest`'s `Next8Hour` ban names something that exists nowhere now.
- `DesktopHeaderDateTest` asserts one exact line of code
  (`DesktopHeaderDate.dailyDate(nowLocal).format(dateFormatter)`); a rename fails it with no bug.

User (2026-11-13): "proceed with your recommendations and address weak points, delete Next8Hour test".

### Weak points addressed (uncommitted)

- **Correction:** `DesktopHeaderDateIntegrationTest` tested header layout only, not which date is
  printed, so the exact-line test was the only thing tying the header to today's date. It is replaced
  by `dailyDateIsTodayWhenTheHourlyViewIsPannedIntoTomorrow`, which renders the popup in the daily
  view with `hourlyOffset = 27` and reads the date text. It fails when the header formats
  `targetHour` again (checked, then restored).
- `HeaderPrecipitationArchitectureTest`: the `Next8Hour` test is deleted.
- `DailyForecastGraphRendererArchitectureTest`: five bans naming things that exist nowhere in main
  sources are removed (`fun getPaintSet(`, `fun drawDayColumn(`, `fun drawTempLabel(`, and the calls
  `DailyForecastGraphRenderer.tempLabelDrawScale` / `.drawTempLabel`). Bans whose name still exists
  in another file stay, since that code could be moved back.
- `DesktopUiArchitectureTest`: unchanged; every name it bans from `Main.kt` exists in the file that
  now owns it.

### Phase 1 leftover: `SilurianApi` (2026-11-13, uncommitted)

`SilurianApiTest` 2 → 11 tests; rule mutants killed 67 → 102 of 120 (56% → 85%). New tests pin: the
daily-only / hourly-only split, `timezone=local`, a naive timestamp anchored at `utc_offset`, rows
dropped for an unreadable time or missing temperature, the daily max/min fallback to `temperature`,
every weather code, the missing-key and non-2xx failures, and the cloud-cover summary log line.
The 18 survivors are coroutine plumbing (`throwOnFailure`, state-machine conditionals) plus the
default-argument lambda of `fetchJson`. No product change.

### Phase 1 leftover: `OpenMeteoApi` (2026-11-13, uncommitted)

`OpenMeteoApiTest` 6 → 12 tests, `PreviousRunsCloudParseTest` 5 → 7; rule mutants killed 71 → 93 of
105 (68% → 89%). No product change.

- `parseSubHourlyHistory` ran in every forecast test but nothing read its output. Now pinned: slots up
  to and including the current time, the reply's zone, dropped slots (no temperature, future, unreadable
  time), clamped cloud layers, and the four cases that return nothing.
- `getCurrent` had no test: fields, clamping, time in the reply's zone, offset time, blank or
  unreadable time, and the two null cases.
- The prior-day cloud tests used `America/Los_Angeles`, the same zone as the dev machine, so the
  fallback to the device zone gave the same answer. A Kathmandu (UTC+5:45) case tells them apart.
- Survivors (12): `return emptyList()` replaced by an empty list (equivalent), two log calls, and
  compiler-generated checks.

### `GoogleWeatherApi` (2026-11-13, uncommitted)

`GoogleWeatherApiTest` 38 → 50 tests; rule mutants killed 209 → 253 of 327 (64% → 77%). No product
change. The request-flow tests (paging, quotas) were already strong; nothing pinned field values.

- Parsing, from handwritten replies: hour fields (rain in inches or mm, visibility in km or miles,
  bounded percentages, rounded cloud), dropped hours, the history/forecast boundary hour, day rain
  chance and day + night rain sum, condition fallback to the night, current conditions, the history
  window's two edges.
- Requests: location, units and per-endpoint sizes; the hour-pages log line; a non-429 history
  failure is retried; history and a blocked product return at the reset instant; with hours blocked
  a daily-only fetch runs and an hourly-only fetch fails without a request.
- Survivors (74): log counters and log calls, request-duration arithmetic, coroutine plumbing, and
  two "forecast missing" branches that no reply can reach.

### `SynopticApi` (2026-11-13, uncommitted)

New `SynopticApiRulesTest` (11 tests); rule mutants killed 139 → 196 of 232 (60% → 84%) before the
last test. No product change. Pinned: the offset-colon rule, what one station entry becomes (QC flag,
condition fallback, METAR vs layer series vs summary for the sky), the single-station outcome matrix,
radius units (miles → km, feet → m) and dropped stations, both requests' parameters and headers, the
token refetch-and-retry, and a dropped connection.

Product observation, not changed: when Synoptic sends a blank `NAME`, the radius `StationInfo.name`
falls back to the station id but that station's observations keep the blank name
(`parseStationObservations` checks only for a missing name). The test does not pin it.

User (2026-11-13): "fix blank station". Fixed: `parseStationObservations` treats a blank `NAME` as
missing, so the observations use the fallback (the station id on the radius path) as the station
record already did. `SynopticApiRulesTest` pins it on both paths; the radius case failed before the fix.

## Phase 5 result (2026-11-13, uncommitted): layer and duplication

**Robolectric classes that need no Android.** 294 files used Robolectric. 35 reference no Android API
directly; 32 of those open Room through `TestDatabase` or are Room host contracts, which need it. The
other three ran unchanged as plain JVM tests and are now `ShortDuration`:
`DailyViewLogicTest` (64 tests, 0.11 s), `AndroidDailyParityTest` (1), `PastDayTripleBarDataIntegrationTest` (3).
The remaining files with one or two Android imports all take a real `Context`
(`ApplicationProvider`), so they stay. Not tried: removing the runner from every class to see what
passes, because `isReturnDefaultValues = true` would let Android stubs return defaults and pass vacuously.

**App tests of `:shared` code.** Seven pure-JVM `:app` test classes touched only `:shared` classes,
so Pitest never saw them (`NiceAxisScale` showed no tests). Moved to `shared/src/test`, same packages:
`SunPositionUtilsTest`, `NavigationUtilsTest`, `NiceAxisScaleTest`, `NwsForecastMapperGridpointMergeTest`,
`ForecastRepositoryPhantomDayTest`, `TemperatureGraphRendererLabelPlacementTest`,
`WeatherSourceHistoricalActualsTest`. `WeatherTimeUtilsTest` stays: it tests the `:app` object of that name.
Desktop: none (the five candidates use the desktop database).

**Duplicates and tests that cannot fail (11 tests removed).**

| Removed | Why | Covering test |
|---|---|---|
| `PrecipTouchZoneTest` (5), `CurrentTempTouchZoneTest` (3) | Assert numbers they declare themselves; read no layout or code | None: they tested nothing |
| `:shared` `TodayColumnOverlayPlannerTest` (1) | Same three cases as the `:app` class, weaker assertions | The `:app` class (4 tests), moved into its place |
| `ZoomLevelSmoothingTest` (2) | WIDE = 3 and NARROW = 1 smoothing passes | `ZoomWindowTest` (wide at line 89; narrow span table, span 5 → 1) |

**Largest files.** No split: `DailyViewLogicTest`'s only concrete problem was the unneeded runner.

Counts: unit tests 6,972 → 6,961; Robolectric files 294 → 291; `./scripts/unit-tests.sh` passes.
