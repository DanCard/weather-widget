# Automated Test Plan: Translation / Localization

**Date:** 2026-07-09
**Scope:** the 20-locale string resources shipped in `values-*/strings.xml` +
`xml/locales_config.xml` (see `summaries/260709-localization-top20-locales.md`), plus the
locale-sensitive runtime paths (`UnitDefaults`, `todayLabel` threading, RTL, widget render).

**Guiding constraints (from repo testing strategy):**
- No mocking framework — pure-function / plain-JUnit first, Robolectric second, emulator last.
- Robolectric has no font engine: text measures ~0 wide. Never assert rendered text *widths*;
  assert resource resolution, format-arg safety, and dp geometry only. Real-font overflow
  checking belongs on the emulator.
- Instrumented tests run via `./scripts/emulator-tests.sh` — never `connectedDebugAndroidTest`.
- Every test must be provably able to fail (break it once before trusting it).

---

> **Status update (2026-07-09, later same day):** Tier 1 is IMPLEMENTED
> (`LocaleResourceParityTest`, all 7 checks, sabotage-verified). A `Localization` topic
> category now exists (orthogonal to duration buckets — declare both in one
> `@Category(...)`): run the slice via `./scripts/unit-tests.sh Localization` or
> `:app:testLocalizationDebugUnitTest`. Tag all future tests from this plan with it.

## Tier 1 — Resource parity (plain JUnit, no Android)

**New file:** `app/src/test/java/com/weatherwidget/util/LocaleResourceParityTest.kt`

Ports the one-off session script (`verify_locales.py`) into a permanent JVM test. Parses
`src/main/res/values/strings.xml` and every `src/main/res/values-*/strings.xml` with
`javax.xml.parsers` (resolve the res dir from `user.dir`, which is the `app/` module dir under
Gradle unit tests; fall back to `app/src/main/res` for IDE runs).

Assertions, one test method each so failures are individually diagnosable:

1. **Key parity** — each locale's key set equals the base's *translatable* key set
   (base minus `translatable="false"`). Catches both missing keys (silent English fallback)
   and extra/stale keys (orphans after a base rename).
2. **Positional format-arg parity** — the multiset of `%N$s` / `%N$d` / `%N$.1f` specifiers in
   each translation equals the base string's. A dropped `%2$s` is a runtime
   `MissingFormatArgumentException` in exactly one language, invisible to aapt.
   A *type-changed* specifier (`%1$d` → `%1$s`) is likewise a one-locale crash.
3. **Literal-percent parity** — `%%` count matches base (position may differ — grammar moved it
   in ja/zh/ko/tr — but count must match, else `UnknownFormatConversionException`).
4. **`formatted="false"` parity** — the three `personal_stations_*` strings carry the attribute
   in every locale (without it, their literal `0%` / `100%` text is a format-parse crash).
5. **No non-translatable leakage** — no locale file defines any of the 5 base
   `translatable="false"` keys (`forecast_history_title_format`, `freshness_unknown_source`,
   `app_logs_verbose`, `app_logs_debug_plus`, `station_type_origin_format`). Keeps lint's
   `MissingTranslation` signal meaningful.
6. **locales_config ↔ folders bijection** — every `<locale android:name>` in
   `xml/locales_config.xml` maps to an existing res folder and vice versa. Encode the two
   non-identity mappings explicitly: `zh-CN` ↔ `values-zh-rCN`, `in` ↔ `values-in`
   (`en` ↔ base `values/`). Catches the classic "added the folder, forgot the picker entry"
   drift — and the reverse, which makes the Android 13+ picker offer a language that
   silently falls back to English.
7. **Placeholder-quote integrity** — the leading/trailing-space strings
   (`forecast_history`, `obs_reported_prefix`, `obs_fetched_separator`, `bias_*_suffix`,
   `accuracy_*_line`) must start/end with the same whitespace as base after XML parsing.
   (The XML parser sees the quoted form's content; if a translator drops the surrounding
   quotes, aapt trims the space and concatenated UI text runs together.)

*Prove-it-can-fail step:* temporarily delete one key from `values-de` and flip one `%1$s` to
`%2$s` in `values-th`; both must go red before the suite is trusted.

**Run:** part of `./scripts/unit-tests.sh` / `./gradlew :app:testDebugUnitTest --tests
"com.weatherwidget.util.LocaleResourceParityTest"` — fast, no emulator, CI-safe.

---

## Tier 2 — Per-locale runtime formatting (Robolectric)

**New file:** `app/src/test/java/com/weatherwidget/ui/LocaleStringFormattingRobolectricTest.kt`
(extends the existing `RobolectricTest` base — `@Config(sdk=[35])`, `LongDuration` category).

Tier 1 proves the XML agrees with itself; this tier proves Android can actually *format* every
string in every locale:

1. **Exhaustive format smoke test.** Reflect over `R.string`'s fields. For each locale in the
   shipped list, `RuntimeEnvironment.setQualifiers(...)` (e.g. `"ar"`, `"zh-rCN"`, `"b+in"` →
   plain `"in"` folder qualifier), then for every string:
   - read the raw value, extract its specifiers;
   - build dummy args by type (`%N$s` → `"x"`, `%N$d` → `1`, `%N$.1f`/`%N$f` → `1.0f`);
   - call `context.getString(id, *args)` — any locale-specific bad specifier throws here.
   Skip strings whose resolved value came from base (`translatable="false"`) — cheap check:
   they have no locale override, formatting them once under base is enough.
2. **RTL configuration sanity.** Under `qualifiers = "ar"` and `"ur"`, assert
   `context.resources.configuration.layoutDirection == LayoutDirection.RTL` and inflate the
   main settings + config layouts (no crash, correct resolved direction). This is a smoke
   test for resource resolution, not a mirroring audit — real mirroring is Tier 3.
3. **Widget placeholder states per locale** — extend the existing `reapply()` pattern
   (`WidgetRenderer` loading / tap-to-refresh / today paths, `WidgetRenderer.kt:79-103`):
   under 3–4 representative qualifiers (`de` longest strings, `ar` RTL, `zh-rCN` CJK, `bn`
   complex script), render the loading state and assert the bound text equals that locale's
   `R.string.widget_loading` / `R.string.today` resolution — i.e. the binder reads resources
   at bind time rather than caching an English string. Use `reapply()`, not `apply()`, per the
   sticky-visibility regression pattern.
4. **`UnitDefaults` regression guard.** Existing `UnitDefaultsTest` covers the CLDR region set.
   Add one Robolectric assertion that the *default test qualifiers* (`en-rUS`) still resolve to
   Fahrenheit — this is the documented assumption that keeps every other Robolectric suite's
   temperature expectations valid; if someone changes default qualifiers it should fail loudly
   here, not as 40 mysterious failures elsewhere.
5. **Per-app language ≠ units.** Set app locale qualifiers to `de` while device region logic
   (via `Resources.getSystem()`) stays `en-rUS`; assert `WidgetStateManager.useCelsius()` is
   still false when the pref is unset. Locks in the deliberate "language picker must not flip
   units" decision.

*Robolectric caveat applied:* nothing in this tier measures text. `Color`/`Paint` stub to 0 and
fonts measure 0 — assertions are about resolution, formatting, and which string got bound.

---

## Tier 3 — Emulator / instrumented (`./scripts/emulator-tests.sh`)

Real fonts, real launcher, real `LocaleManager`. Keep this tier small — it's the slow lane
(WorkManager teardown stalls make each class cost more than its body).

1. **Enable pseudolocales in debug builds** (one-line build change, not currently set):
   `android { buildTypes { debug { pseudoLocalesEnabled = true } } }`.
   Pseudolocale `en-XA` expands strings ~30% and brackets them; `ar-XB` force-RTLs — the two
   cheapest proxies for "will German overflow" and "does RTL mirror".
2. **Per-app locale switch → widget re-render.**
   New `LocaleSwitchIntegrationTest` following the `AddWidgetIntegrationTest` recipe
   (grant + bind → wait for `WIDGET_RENDER_OK` breadcrumb):
   - bind a widget, confirm `WIDGET_RENDER_OK`;
   - `LocaleManager.setApplicationLocales(LocaleList.forLanguageTags("ar"))` (API 33+);
   - send `ACTION_REFRESH`, assert a fresh `WIDGET_RENDER_OK` breadcrumb and no crash /
     `PROC_EXIT` row. Repeat for `en-XA` (expansion) and `zh-CN` (folder-mapping proof:
     the `zh-CN` picker entry must actually resolve `values-zh-rCN`, and `in`/`id` aliasing
     must resolve `values-in`). The `id`→`in` and `zh-CN`→`zh-rCN` cases are exactly where a
     static bijection test can't prove the *platform* agrees with us — only this test can.
3. **Screenshot artifact sweep (report-only, no assertions).** For each of
   {`en-XA`, `ar-XB`, `de`, `bn`, `th`}: set app locale, refresh, `screencap` → convert to JPG
   (per CLAUDE.md adb-PNG caveat), store as CI/test artifacts named by locale. Overflow and
   mirroring defects are visual; an automated diff would be flaky, but an automated *capture*
   makes the human review a 30-second scan instead of 5 manual device-language changes.
   (This is deliberately not an assertion — Robolectric can't do it and pixel-diffing across
   emulator images churns.)

---

## Tier 4 — Lint & CI wiring

1. **Promote `MissingTranslation` and `ExtraTranslation` to errors** in `app/build.gradle.kts`
   `lint {}` block. The `translatable="false"` hygiene from the es/fr/uk pass was done
   precisely so this stays signal, not noise. This is the compile-time twin of Tier 1 checks
   1 and 5 — keep both: lint runs on release builds; the JUnit test runs in the fast lane and
   gives a better failure message.
2. **Suite placement:** Tier 1 + 2 ride the existing `./scripts/unit-tests.sh` invocation
   automatically (they live in `app/src/test`). Tier 3 rides `./scripts/emulator-tests.sh`.
   No new CI plumbing needed.

---

## Explicitly out of scope

- **Translation *quality*** — machine-quality wording, honorific register, terminology
  consistency. Not automatable meaningfully; needs native review before store rollout.
- **Desktop app** — reads no Android resources; stays English (existing decision).
- **Widget text-width assertions in Robolectric** — impossible (no font engine); covered
  by Tier 3 screenshots instead.
- **Plurals** — the app currently uses zero `<plurals>`; if one is ever added, extend Tier 1
  with per-locale `quantity` coverage (ru/pl/uk need `few`/`many`; ja/zh/ko/th/vi/id need
  only `other`) — noted here so the gap is known.

## Implementation order

1. Tier 1 `LocaleResourceParityTest` (highest value per minute; replaces the scratchpad
   script that dies with the session).
2. Tier 4 lint promotion (one-liner, closes the release-build gap).
3. Tier 2 formatting smoke test + widget locale-bind tests.
4. Tier 3 `pseudoLocalesEnabled` + `LocaleSwitchIntegrationTest`.
5. Tier 3 screenshot sweep (nice-to-have; do before Play rollout).
