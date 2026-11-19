# Split oversized Android suspend functions (VerifyError risk)

Status: **approved 2026-11-19**

## Request

User, 2026-11-19: "proceed with full refactor" (after the VerifyError fix; Claude had proposed
splitting the large suspend functions as the higher-value refactor).

## Evidence

- 2026-11-19: ART rejected `CloudCoverViewHandler.updateWidget` (~500-line suspend function):
  `VerifyError … copy-cat2 v3<-v129 type=Long (Low Half)/Zero/null`. The dex was invalid on every
  build; it crashed only runs that loaded the class. Fixed in `11815ea3a` by extracting
  `bindCloudGraph`; guard `WidgetHandlerClassVerificationInstrumentedTest`.
- Before the split that method was larger than the 7,990 instructions / 145 registers it has now.
  Dexdump of today's debug APK, `com.weatherwidget` methods by instruction count (all suspend):

| Method | insns | regs | source lines |
|---|---:|---:|---:|
| `FullSyncPipeline.run` | 30,646 | 456 | 372 |
| `WidgetStartupCoordinator.updateWidgets` | 17,548 | 151 | 226 |
| `PrecipViewHandler.updateWidget` | 17,026 | 311 | 490 |
| `DailyViewHandler.updateWidget` | 13,704 | 301 | 373 |
| `TemperatureStateResolver.resolve` | 12,898 | 270 | 390 |
| `ForecastRepository.getWeatherData` | 9,242 | 61 | — |
| `CurrentConditionsRun.run` (:shared) | 8,688 | 100 | — |
| `TemperatureGraphHoursLoader.loadGraphHours` | 8,130 | 178 | 210 |
| `CloudCoverViewHandler.updateWidget` (after fix) | 7,990 | 145 | — |
| `DailyInteractionRenderer.render` | 7,700 | 200 | 191 |
| `DailyGraphRenderer.render` | 7,382 | 132 | 404 |
| `WeatherWidgetWorker.handleCurrentTempOnlyWork` | 7,314 | 55 | — |

- They all pass verification today. The failure was a code-generation defect that a one-line
  addition tipped (`b786b019e`), so "passes today" is not a margin. **Unproven:** the exact
  trigger (register count, method size, or the spill shape); the budget below is a heuristic.
- Desktop runs on the JVM, whose verifier does not share this problem; the plan covers code that
  ships in the APK (`:app`, and `:shared` methods compiled into it).

## Proposed fix (Claude proposal)

1. **Guard every class, not a list.** `scripts/emulator-tests.sh`: after installing, run
   `adb shell cmd package compile -m verify -f com.weatherwidget` and fail the device's run on any
   logcat `Verification error` naming `com.weatherwidget`, printing the method. Keeps
   `WidgetHandlerClassVerificationInstrumentedTest` (fast, explicit) and adds the split classes to it.
2. **Make size visible.** `scripts/dex-method-budget.sh`: dexdump the debug APK and list
   `com.weatherwidget` methods over a budget of **6,000 insns or 150 registers** (start as a report in
   the staggered gate summary; turn into a failure once phase 2 lands). Budget chosen to sit under the
   method that failed, not derived from a known threshold.
3. **Phase 1 — the four largest** (each > 12,000 insns and > 250 registers, or 17,548 insns):
   `FullSyncPipeline.run`, `PrecipViewHandler.updateWidget`, `DailyViewHandler.updateWidget`,
   `TemperatureStateResolver.resolve`, then `WidgetStartupCoordinator.updateWidgets` (inlined lambdas;
   check what inlines before splitting).
4. **Phase 2 — the rest over budget** in the table, highest registers first.

### How each split is done

- Extract whole phases (load / decide / render / bind / log) into private suspend or plain functions
  that take what they read and **return a small data class** of what the caller needs, as
  `bindCloudGraph` → `CloudGraphTimings`. No behavior change, no new state, no reordering of
  suspension points or logging.
- Prefer non-suspend helpers for pure computation (they carry no coroutine spill state).
- Mutable `var` locals that live across suspension points and are assigned on some branches (the
  `var buildHoursMs = 0L` shape) become return values.
- One function per commit-sized step; re-verify (`compile -m verify`) after each.

## Tests

- Existing behavior tests must stay green unchanged (this is a pure refactor); no test is edited to
  fit the new structure except imports.
- Each split class joins `WidgetHandlerClassVerificationInstrumentedTest`.
- The new emulator verify step is shown to fail on `11815ea3a^` (the unfixed APK), then pass.

## Verification

- `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
- `dex-method-budget.sh` before/after table in the summary.
- On-device: daily, temperature, precip and cloud views on the Fold 4 and emulator, plus a full sync
  (`FullSyncPipeline`) with logs checked for unchanged `WIDGET_RENDER_PERF` / sync lines.

## Docs

- `arch/testing-strategy.md`: the verify step and the budget report.
- `arch/operations.md`: the `compile -m verify` command for diagnosing a `VerifyError`.

## Open questions

1. Budget: report-only first, then failing (proposal), or failing from the start?
2. Phase 2 now, or after phase 1 has run on the phones for a few days?

## Decisions (user, 2026-11-19)

- Approved as written; order: split first (verify step, phase 1, phase 2), then the renames.
- Budget: report first, then fail once phase 2 lands.

## Findings during implementation (2026-11-19)

- **The defect reproduces from source.** With the verify step working, a clean build of `b786b019e`
  (= `11815ea3a^`) and the unfixed `CloudCoverViewHandler.kt` compiled against today's tree are both
  rejected on Generic_Foldable_API36 and Medium_Phone_API_36 (same image, ART module 360527520);
  current `main` verifies on both. The premise above stands.
- A first cut of `verify_app_dex` passed everything: unquoted, `adb shell date +'%m-%d …'` failed on
  the device, `since` was empty and `logcat -T ""` printed only its last line. For about an hour that
  made it look as if the defect came only from incremental builds; that conclusion was withdrawn. The
  step now rejects an unreadable device time and was shown to fail on both pre-fix APKs.
- Steps 1 (verify) and 2 (budget report) are implemented. Next: phase 1 splits.
- **What makes them big:** `DailyViewHandler.updateWidget`'s bytecode is dominated by 252 calls to
  `kotlin.coroutines.jvm.internal.SpillingKt.nullOutSpilledVariable` — the coroutine codegen nulling
  dead locals at each suspension point, so size grows with (live locals × suspension points). The
  rejected register held "Long (Low Half)/Zero/null", the value a nulled slot holds, read as a `long`:
  a plausible mechanism (spill-nulling of a `long` local), **not confirmed**. Splits that move locals
  and suspension points into helpers attack exactly that product.
- Done so far: `PrecipViewHandler.updateWidget` 17,026 insns/311 regs → 7,220/122 (+ `bindPrecipGraph`
  6,110/132); `DailyViewHandler.updateWidget` 13,704/301 → 9,848/247 (+ `resolveHeaderPhase`,
  `bindTextMode`).
- **Release builds (user approved 2026-11-19, after a side note that only debug was checked):** debug and
  release share `com.weatherwidget` with different signing keys, so they cannot be installed side by
  side. `scripts/verify-apk-dex.sh` verifies an APK or AAB on an emulator without installing it; the
  gate's `verify_app_dex` and Fastlane's `build` lane both use it. Shown to fail on the `b786b019e` APK
  and on its dex in AAB layout; today's R8 release AAB (1 dex) verifies clean.
- Phase 1 sizes: `FullSyncPipeline.run` 30,646/456 → largest piece `onFetchSuccess` 7,398/202 (+
  `loadAndBackfill`, `recomputeActuals`); `TemperatureStateResolver.resolve` 12,898/270 → 7,364/187 (+
  `renderGraphPhase` 4,064/133; the Loaded/Empty `when` no longer fills seven `var`s).

## Phase 2 handoff (2026-11-19, for a fresh session)

**State:** steps 1–2 and phase 1 are committed (`0a0153dc3`, release boot `bcef588b2`); the gate is
green on Medium_Phone_API_36 and Pixel_Tablet_API36. Phase 2 has not started. The rename plan
(`plans/261119-rename-screen-code-names.md`) comes after it and is approved.

**Targets** (`scripts/dex-method-budget.sh`, budget 6,000 insns or 150 registers; order: suspend
functions first, most registers first):

| # | Method | insns | regs | Source |
|---|---|---:|---:|---|
| 1 | `DailyViewHandler.updateWidget` (left over from phase 1) | 9,848 | 247 | `app/…/widget/handlers/DailyViewHandler.kt` |
| 2 | `DailyBlendRecomputeRun.day` (:shared) | 5,954 | 210 | `shared/…/shared/actuals/DailyBlendRecomputeRun.kt:124` |
| 3 | `FullSyncPipeline.onFetchSuccess` (phase 1 piece) | 7,398 | 202 | `app/…/widget/FullSyncPipeline.kt` |
| 4 | `DailyInteractionRenderer.render` | 7,700 | 200 | `app/…/widget/handlers/DailyInteractionRenderer.kt:207` |
| 5 | `TemperatureStateResolver.resolve` (phase 1 piece) | 7,364 | 187 | `app/…/widget/handlers/TemperatureStateResolver.kt` |
| 6 | `TemperatureGraphHoursLoader.loadGraphHours` | 8,130 | 178 | `app/…/widget/handlers/TemperatureGraphHoursLoader.kt:41` |
| 7 | `CloudCoverViewHandler.updateWidget` (after its fix) | 7,990 | 145 | `app/…/widget/handlers/CloudCoverViewHandler.kt` |
| 8 | `DailyGraphRenderer.render` | 7,382 | 132 | `app/…/widget/handlers/DailyGraphRenderer.kt:51` |
| 9 | `PrecipViewHandler.bindPrecipGraph` / `.updateWidget` | 6,110 / 7,220 | 132 / 122 | `app/…/widget/handlers/PrecipViewHandler.kt` |
| 10 | `CurrentConditionsRun.run` (:shared) | 8,688 | 100 | `shared/…/shared/observations/CurrentConditionsRun.kt:78` |
| 11 | `ForecastRepository.getWeatherData` | 9,242 | 61 | `app/…/data/repository/ForecastRepository.kt:204` |
| 12 | `WeatherWidgetWorker.handleCurrentTempOnlyWork` | 7,314 | 55 | `app/…/widget/WeatherWidgetWorker.kt:375` |
| 13 | `TemperatureExtrema.compute` (:shared, **not suspend**: no spill state; last, or exempt) | 6,795 | 65 | `shared/…/shared/graph/TemperatureExtrema.kt:60` |

Then make the budget fail the gate (`dex-method-budget.sh` exit 1 when over; decide the exemption for
non-suspend methods), per the user's "report, then fail".

**Technique that worked in phase 1** (no behavior change):
1. Measure: `MAX_INSNS=0 MAX_REGS=0 scripts/dex-method-budget.sh | grep <Class>` after `assembleDebug`.
2. Find phase boundaries at the function's top-level statements (`awk` lines indented 8 spaces).
3. For a candidate block, list the outer names it reads, the names it declares that later code reads,
   outer `var`s it assigns, and any `return` (early exit) — a short Python `re` scan did this.
4. Extract to a private suspend (or plain, if pure) function taking exactly those reads and returning a
   small class of exactly those later-read values. An early `return` becomes a nullable result and
   `?: return` at the call (`bindPrecipGraph`). `Result.fold`/`forEach` with suspend bodies inline into
   the caller — move their bodies out (`FullSyncPipeline.onFetchSuccess`).
5. Replace conditionally assigned `var x = 0L` / `var … = null` locals with returned values or
   `val loaded = when (…) { Empty -> return …; Loaded -> it }` (`TemperatureStateResolver`).
6. `./gradlew :app:compileDebugKotlin`, re-measure, then the class's existing tests; add the class to
   `WidgetHandlerClassVerificationInstrumentedTest`.
7. Per batch: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh` (runs
   `verify_app_dex` on every emulator), then commit (user, 2026-11-19: "commit when you think it is a
   good idea").

**Pitfalls seen:** `bash` heredocs with `$` in Python; the Kotlin `Name contains illegal characters: ;`
in backtick test names; a stale `app/build` APK after a `git checkout` round-trip (rebuild before
measuring); do not edit `scripts/*.sh` while a gate runs (bash reads scripts as it executes).

## Phase 2 results (2026-11-19)

All 13 targets split, same technique; `dex-method-budget.sh` now reports 0 of 28,227 methods over and
**fails the gate** when any is (`REPORT_ONLY=1` to measure). No exemption for non-suspend methods was
needed: `TemperatureExtrema.compute` split cleanly.

| Method | before insns/regs | after | largest new piece |
|---|---:|---:|---|
| `DailyViewHandler.updateWidget` | 9,848 / 247 | 5,434 / 141 | `resolveHeaderPhase` 1,520 / 85 |
| `DailyBlendRecomputeRun.day` | 5,954 / 210 | 3,340 / 138 | `persist` 1,446 / 42 |
| `FullSyncPipeline.onFetchSuccess` | 7,398 / 202 | 802 / 31 | `paintAll` 976 / 34 |
| `DailyInteractionRenderer.render` | 7,700 / 200 | 1,108 / 35 | `assemble` 3,920 / 96 |
| `TemperatureStateResolver.resolve` | 7,364 / 187 | 4,214 / 87 | `buildHeaderAndGraph` 1,848 / 81 |
| `TemperatureGraphHoursLoader.loadGraphHours` | 8,130 / 178 | 1,980 / 63 | `logBlendDiagnostics` 2,108 / 55 |
| `CloudCoverViewHandler.updateWidget` | 7,990 / 145 | 4,322 / 78 | `bindCloudHeader` 1,470 / 77 |
| `DailyGraphRenderer.render` | 7,382 / 132 | 4,040 / 74 | `bindTapTargets` 1,336 / 46 |
| `PrecipViewHandler.updateWidget` / `bindPrecipGraph` | 7,220 / 122, 6,110 / 132 | 3,782 / 70, 4,018 / 115 | `bindPrecipHeader` 1,458 / 73 |
| `CurrentConditionsRun.run` | 8,688 / 100 | 1,014 / 39 | `runLocked` 1,674 / 62 |
| `ForecastRepository.getWeatherData` | 9,242 / 61 | 3,394 / 55 | `planSyncUnderLock` 1,268 / 48 |
| `WeatherWidgetWorker.handleCurrentTempOnlyWork` | 7,314 / 55 | 4,236 / 47 | `fetchCurrentTemp` 912 / 33 |
| `TemperatureExtrema.compute` (not suspend) | 6,795 / 65 | 2,259 / 49 | `perDayActualExtrema` 3,383 / 45 |

Shapes removed along the way: conditionally assigned `var`s (`TemperatureGraphHoursLoader`'s four
`obs…` vars → `ObsRead`; the worker's five → `CurrentFetchOutcome`; its `cacheRefreshDurationMs`), and
phase objects unpacked into locals that lived across every later suspension (`FullSyncPipeline`,
`TemperatureStateResolver`). `CurrentConditionsRun.run`'s inlined `mutex.withLock` body is now
`runLocked`; its non-local early `return` became a plain return under the same lock.
`WidgetHandlerClassVerificationInstrumentedTest` loads every split class.
