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
