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
