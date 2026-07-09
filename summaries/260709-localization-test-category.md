# Localization test category + LocaleResourceParityTest (test plan Tier 1)

**Date:** 2026-07-09
**Follows:** `notes/260709-localization-testplan.md`

## Design: topic category, orthogonal to duration buckets

The existing Short/Medium/Long categories are a complete *partition* by runtime.
`Localization` is a *topic slice* layered on top, not a fourth duration:

- Every test class still declares exactly one duration category
  (`validateUnitTestDurations` unchanged — it only counts duration names, so extra topic
  markers pass).
- JUnit's `@Category` is **not repeatable**: duration + topic go in ONE annotation —
  `@Category(ShortDuration::class, Localization::class)`. A Robolectric subclass declaring
  its own `@Category` *replaces* the base class's inherited `LongDuration`, so it must
  restate the duration alongside the topic.
- Localization tests therefore run twice as often as untagged ones is NOT true — they run
  once in their duration bucket during normal runs; the topic task is an on-demand re-run
  of just the slice.

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

## Notes

- `:shared` has no category infrastructure; `UnitDefaultsTest` (localization-adjacent)
  stays untagged there — shared tests are fast pure-JVM and always run wholesale via
  `unit-tests.sh`.
- Future localization tests from the plan (Tier 2 `LocaleStringFormattingRobolectricTest`,
  etc.) must be tagged `@Category(<Duration>::class, Localization::class)`.
