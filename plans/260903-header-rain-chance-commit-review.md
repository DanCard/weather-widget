# Review: `49ba82a7` header rain chance six-hour window

**Date:** 2026-09-03

**Status:** Review complete; approved remediation implemented and verified

**Reviewed commit:** `49ba82a7db7776ba28cb7fce491854586a98782c` (`Switch header rain chance % rolling window from 8 hours to 6 hours`)

## Scope and evidence

This review covered the commit diff, its predecessor (`69720435`), the prior plan and summary,
all production references to the six/eight-hour precipitation APIs, and the nearest shared,
Android, and desktop tests. The worktree was clean at review start.

The intended behavior is sound: the header, today's click-routing gate, and the forward side of
`ZoomStage.WIDE` should use the same six-hour horizon. Production callers in all three modules now
select the six-hour functions. The findings below concern API correctness, missing regression
coverage, duplicate resolution, and cross-platform ownership left by the commit.

## Findings

### F1 — High: APIs named “next 8 hour” now silently calculate six hours

**Evidence**

1. `shared/.../PrecipProbabilityCalculator.kt:44-57` retains
   `getNext8HourPrecipProbability`, but delegates to `getNext6HourPrecipProbability`.
2. `shared/.../PrecipProbabilityCalculator.kt:150-165` does the same for
   `isNext8HourPrecipPredominantlyNight`.
3. `app/.../HeaderPrecipCalculator.kt:25-36` and `:70-83` repeat the misleading aliases at the
   Android boundary.
4. `DailyViewLogic.kt:386-415` retains a test-only overload whose named parameter is
   `todayNext8HourPrecipProbability` but passes it into the new generic/six-hour path.
5. Current Android tests still call these aliases and use old eight-hour names. The shared alias
   test explicitly asserts that an API named eight hours behaves as six hours.

**Impact**

This is not behavioral backward compatibility: source compatibility was preserved by changing the
meaning of the old contract. Any current test or future caller that reasonably requests an
eight-hour value gets six hours without a compile failure or visible warning. The shims also keep
obsolete terminology alive and make repository searches unreliable during later precipitation
work.

**Required remediation**

Remove the shared and Android eight-hour aliases and remove the test-only `DailyViewLogic`
overload. Update every remaining test/caller to the six-hour or horizon-neutral name. If a genuine
eight-hour use case is found during implementation, retain an eight-hour API only if it passes
`lookaheadHours = 8L`; do not retain a semantically false delegate. This repository does not expose
these helpers as a published compatibility API, so internal call-site migration is preferable to
permanent misleading surface area.

### F2 — Medium: the changed nighttime horizon has no boundary regression

**Evidence**

1. The production night loop was correctly parameterized in
   `PrecipProbabilityCalculator.kt:167-201`, which is the behavior-sensitive part of the commit.
2. `PrecipProbabilityCalculatorNightTest.kt:13` still documents the old eight-hour function.
3. Its “night probability mass exceeds day” fixture (`:56-70`) still describes two daytime hours
   plus six nighttime hours. It returns `true` under both the former eight-hour window and the new
   six-hour window, so it cannot prove the horizon changed.
4. The Android night tests likewise retain eight-hour method/test names and scenarios that do not
   distinguish six from eight hours.

**Impact**

A regression that restores an eight-hour night-weighting loop would pass the current night suite.
The displayed probability can therefore be six-hour-correct while the daily-view font shrink is
still decided from rain outside the visible window.

**Required remediation**

Add a shared boundary test where daytime probability dominates `[now, now + 6h)`, but rain between
`+6h` and `+8h` would flip the verdict to nighttime. Assert the six-hour public resolver remains
daytime. Add the complementary exact-end/exclusion case and rename stale class comments/tests.
Keep detailed interpolation/source-selection coverage in `:shared`; keep only Android adapter and
render-state wiring assertions in `:app`.

### F3 — Medium: Android resolves the daily header, then the graph renderer resolves part of it again

**Evidence**

1. `DailyHeaderResolver.kt:182-201` computes `precipProb`, `isNightPrecip`, and the final
   `precipTextSizeDp`, then stores the size in `DailyViewHandler.HeaderState` (`:836-857`).
2. `DailyGraphRenderer.kt:222-228` reruns the shared six-hour nighttime calculation over all hourly
   rows, and `:246-249` rebuilds the text size instead of consuming
   `headerState.precipTextSizeDp`.
3. `DailyGraphRenderer.render` already receives the complete `HeaderState`, so no additional
   resolution is required.

**Impact**

The graph path performs another entity-to-shared-model allocation and another 360-minute
interpolation pass per render. More importantly, two components own the same header decision; a
future input, horizon, source-selection, or sizing change can make the RemoteViews and bitmap
headers disagree.

**Required remediation**

Make `DailyHeaderResolver` the single Android owner of header precipitation resolution. Pass the
already resolved `HeaderState.precipTextSizeDp` to `HeaderRenderData` and delete the graph-side
night recomputation. Add a regression asserting that the graph header consumes the resolved state,
including the night-scaled size, without independently resolving hourly precipitation.

### F4 — Medium: the cross-platform policy is shared only at the lowest level

**Evidence**

1. `PrecipProbabilityCalculator.DEFAULT_LOOKAHEAD_HOURS` and
   `DayClickResolver.TODAY_LOOKAHEAD_HOURS` are separate `6L` constants even though the commit's
   stated purpose is one identical horizon.
2. Android's `HeaderPrecipCalculator` and desktop's `WidgetHeader` separately orchestrate the same
   source IDs, fallback, reference time, probability resolution, and night resolution.
3. Header font scaling is also split: Android combines
   `DailyRainLabels.precipProbabilityScaleFactor` and `NIGHT_SCALE` at call sites, while desktop
   duplicates that combination in `HeaderPrecipSizing.headerPrecipFontScale`.
4. `HeaderPrecipSizing.kt:14-28` still says “next-8h,” showing that duplicated policy/documentation
   has already drifted in the same commit.

**Impact**

Android and desktop share interpolation primitives but still own duplicate policy assembly. The
two lookahead constants, source/fallback wiring, paired probability/night calls, and sizing formula
can change independently. This falls short of the project's maximum-sharing requirement.

**Required remediation and ownership**

1. In `:shared`, introduce one explicitly named six-hour header/today-window policy constant and
   have `DayClickResolver` reference it. Keep the relationship to `ZoomStage.WIDE.forwardHours`
   covered by a shared test so a graph-window change fails loudly.
2. In `:shared`, add a cohesive header precipitation resolver that accepts shared
   `HourlyForecast` rows and returns a small immutable result containing the resolved probability
   and predominantly-night decision. Internally normalize/select the precipitation series once so
   both values use identical source and timestamp treatment.
3. In `:shared`, own the platform-neutral font scale formula (probability scale multiplied by the
   daily-only night factor). Android and desktop should apply only their platform base text size.
4. Keep Android's adapter only for `HourlyForecastEntity -> HourlyForecast` conversion and Android
   dp sizing. Desktop should call the shared resolver/result directly. Remove the desktop-only
   policy formula from `HeaderPrecipSizing`; retain only a desktop base-size constant if that makes
   the Compose call site clearer.
5. Extract the precipitation-resolution block from the 2,041-line desktop `Main.kt` into a small
   desktop adapter/model function or file. `WidgetHeader` should render an already resolved model,
   not assemble shared weather policy inline.

## Implemented remediation

1. Added discriminating shared nighttime-boundary, exact-end exclusion, and wide-window parity
   tests.
2. Added shared `HeaderPrecipitation` resolution, one shared visible-window constant, and shared
   daily-only night/font scaling.
3. Reduced Android's helper to Room-model conversion and Android dp sizing. `DailyHeaderResolver`
   now resolves the paired header result once, and `DailyGraphRenderer` consumes the resulting
   `HeaderState.precipTextSizeDp`.
4. Extracted desktop precipitation orchestration from `Main.kt` into
   `DesktopHeaderPrecipitationResolver`, which delegates the weather policy and font scaling to
   `:shared`.
5. Deleted the false eight-hour aliases, the test-only `DailyViewLogic` overload, the duplicated
   desktop sizing policy, and obsolete platform tests. Added thin adapter and architecture tests.

## Behavior invariants

1. The interval remains half-open: `[referenceTime, referenceTime + 6h)`; a point at `+7h` cannot
   affect probability or night sizing.
2. Display-source rows remain preferred; generic-gap rows are used only when the display source has
   no usable probability rows.
3. Daily probability remains the header fallback when hourly data cannot produce a value.
4. Night shrink remains daily-view-only; hourly, precipitation, and cloud headers use probability
   scaling without the night factor.
5. Android and desktop resolve the same probability, nighttime verdict, and scale for equivalent
   shared inputs.
6. Today's day-click routing retains its explicit daily fallback/audit-source behavior.

## Verification record

1. Focused shared, Android, and desktop tests passed, including the discriminating six-versus-eight
   night fixture, exact six-hour endpoint exclusion, thin Android adapter, resolved-state wiring,
   and extracted desktop resolver.
2. `./gradlew test` passed across all modules.
3. `./gradlew ktlintCheck assembleDebug :desktop:createDistributable` passed.
4. Emulator `emulator-5554` (Google `sdk_gphone64_x86_64`) rendered widget 59 in daily mode with
   `precipProbability=6`; logcat recorded the matching `DailyViewHandler` state and successful
   `WIDGET_PAINT`, and the screenshot showed the 6% header. No fatal Android runtime entry appeared.
5. The live data did not contain a convenient six-versus-eight nighttime discriminator. That
   boundary is therefore verified deterministically in shared tests, while the emulator evidence
   verifies the integrated Android render path. Desktop behavior is verified through the extracted
   resolver tests and distributable build; no desktop GUI session was launched.
6. Emulator widget 59 was restored after verification to the recorded source `NWS`, view
   `TEMPERATURE`, hourly offset `-7`, zoom `NARROW`, and daily offset `-1`.

## Out of scope

Broader decomposition of `DailyViewLogic` and desktop `Main.kt` beyond the precipitation
responsibilities was not required to make this feature cohesive. The precipitation block was
extracted from `Main.kt`; unrelated responsibilities remain untouched.
