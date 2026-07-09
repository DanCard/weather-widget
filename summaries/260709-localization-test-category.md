# Localization test category + LocaleResourceParityTest (test plan Tier 1)

**Date:** 2026-07-09
**Follows:** `notes/260709-localization-testplan.md`

## Design: Localization is a PEER bucket (revised same day, per user decision)

> Originally built as a topic tag riding alongside a duration category
> (`@Category(ShortDuration::class, Localization::class)`). User decision: localization
> tests live in their **own** category, not in a duration bucket. Final model:

Four buckets — Short, Medium, Long, Localization — **partition** the suite; every test
class declares exactly ONE: `@Category(Localization::class)` alone for localization tests.

- Because the buckets partition the suite, the **default run must include Localization**:
  `unit-tests.sh` defaults are now `Short Medium Long Localization`, and
  `testByDurationDebugUnitTest` (name kept for muscle memory) depends on all four bucket
  tasks. Dropping a bucket from a run silently drops its tests entirely.
- One map (`unitTestCategoryBuckets`) is the single source of truth: task registration,
  the aggregate, and the validator whitelist all derive from it.
- A Robolectric localization test declaring `@Category(Localization::class)` *replaces*
  the `LongDuration` inherited from the `RobolectricTest` base class (Java annotation
  inheritance: nearest declaration wins) — which is exactly what this model wants.

## Changes

- `app/src/test/.../test/category/Localization.kt` — marker interface.
- `app/build.gradle.kts` — `unitTestTopicCategories` map + registration through the same
  task factory as durations → `:app:testLocalizationDebugUnitTest` (+`Fresh`). Deliberately
  NOT added to `testByDurationDebugUnitTest` (the aggregate stays the duration partition).
- `scripts/unit-tests.sh` — accepts `Localization` as an explicit bucket
  (`./scripts/unit-tests.sh Localization`); never in the defaults. Also fixed a latent
  bug: the aggregate-task shortcut fired on ANY three buckets (`Short Medium Localization`
  would have wrongly run the duration aggregate) — now matches exactly `Short Medium Long`.
- `LocaleResourceParityTest.kt` — Tier 1 of the test plan, first member of the category
  (plain JVM, parses res XML, no Robolectric). 8 test methods: key parity, positional
  format-arg parity, bare-specifier parity, `%%` counts, `formatted=` attribute parity,
  non-translatable leakage, locales_config↔folder bijection (encodes `zh-CN`↔`values-zh-rCN`,
  `in`↔`values-in`, `en`↔base), and edge-whitespace preservation with a fullwidth-punctuation
  exemption (CJK `：（）` legitimately absorb the ASCII space; e.g. zh `"预报历史："`).
- Base `forecast_history` now quoted (`"History of Forecasts for "`) — it was unquoted, so
  aapt silently trimmed the trailing space in English only, while all 19 translations
  shipped quoted. Cosmetic (used as design-time text + contentDescription) but the parity
  test would rightly have flagged the inconsistency.

## Verification

- All 8 checks pass via `:app:testLocalizationDebugUnitTest` and via the Short bucket
  (orthogonality confirmed both ways).
- **Proved it can fail** (repo rule): deleted `save_location` from values-de and flipped
  `%1$s`→`%2$s` in values-th → exactly the right two tests failed with
  `[de] missing: [save_location]` and
  `[th] stats_error_loading: base args [%1$s] but translation has [%2$s]`; restored, green.
- `validateUnitTestDurations` passes (combined annotation counts as one duration).
- `:app:ktlintCheck` clean.

## Category-integrity guard (validateUnitTestDurations, strengthened)

The validator (which every Test task depends on — unskippable) now enforces:

- **exactly one category bucket per file** — zero means the tests run in NO bucket
  (JUnit category filtering fails open); two means double execution across the full run;
- **exactly one marker per `@Category(...)` annotation** — the old combined
  duration+topic style is now itself a violation;
- **only known markers** — whitelist derived from `unitTestCategoryBuckets`, so a typo'd
  or unregistered marker fails the build instead of silently filtering the test out.

The check keys on files, matching the repo's one-test-class-per-file convention.

Proved-can-fail (both iterations): `@Category(ShortDuration, MediumDuration,
ShortDuration, BogusCategory)` and the now-illegal `@Category(ShortDuration,
Localization)` each produced the expected violations; removed, green.

## Audit of existing tests (which got tagged, which didn't, and why)

Searched all of `app/src/test` for genuine locale/language behavior (`Locale.setDefault`,
`setQualifiers`, `forLanguageTag`, `LocalePreferences`, `useCelsius` defaults, name hints):

- **Tagged:** `WidgetFormatUtilsTest` — runs `formatPrecipAmount` under both `Locale.US`
  and `Locale.GERMANY` and asserts locale-driven unit selection (in vs mm). That's a
  locale→behavior contract, so it belongs in the slice (26 tests total with the parity test).
- **Not tagged, deliberately:**
  - `DailyGapFallbackGraphIntegrationTest` — pins `Locale.US` purely for deterministic
    formatting in an integration test; locale hygiene, not a localization test.
  - The ~88 `todayLabel = "Today"` call sites across graph-logic tests — they thread a
    label parameter; the tests are about geometry, not language.
  - `UnitDefaultsTest` (`:shared`) — the most localization-shaped test in the repo, but
    `:shared` has no category infrastructure (markers live in `app/src/test`) and shared
    tests always run wholesale via `unit-tests.sh`, so a tag there would filter nothing.

## Notes

- `:shared` has no category infrastructure; `UnitDefaultsTest` (localization-adjacent)
  stays untagged there — shared tests are fast pure-JVM and always run wholesale via
  `unit-tests.sh`.
- Future localization tests from the plan (Tier 2 `LocaleStringFormattingRobolectricTest`,
  etc.) must be tagged `@Category(<Duration>::class, Localization::class)`.
