# Detect and prevent hardcoded user-facing strings

## Background

Commit `95c87a92` fixed one hardcoded string — `"Actual cloud cover data from <provider>"` — that
lived as a Kotlin literal in `:shared` (`DominantStationLabel.formatCloudSourceLabelText`) and was
therefore English-only on Android regardless of locale. The fix moved the prose to the platform
boundary (`Context.getString(R.string.actual_cloud_cover_data_from, …)`) and added a Robolectric
regression test.

The structural gap that allowed it: the existing localization guards (`LocaleResourceParityTest`,
`LocaleStringFormattingRobolectricTest`, lint `MissingTranslation`/`ExtraTranslation`) only validate
strings that **already exist as resources**. A literal that never gets a resource key is invisible
to all of them.

This plan adds a guard for that gap: a test that scans Kotlin source for hardcoded user-facing
strings, plus a triage of the occurrences such a test finds.

## Evidence: occurrences found

All of these are English-only user-facing text that bypasses `R.string` on Android.

### A. Widget canvas text (visible to every user — same class as `95c87a92`)

1. `app/.../widget/CloudCoverGraphRenderer.kt:684–693` — `"Cloud data unavailable"`,
   `"Cloud data missing for X of Y hrs"`, `"Cloud data missing at …"`,
   `"Cloud data missing … (X of Y hrs)"`. Drawn via `canvas.drawText`.
2. `app/.../widget/GraphFailureWatermarkRenderer.kt:139–140` — `"<SOURCE> UPDATES FAILING"` /
   `"UPDATES FAILING"`.
3. `app/.../widget/GraphFailureWatermarkRenderer.kt:211–226` — `humanReadableErrorCode`:
   `"400 Bad Request"`, `"401 Unauthorized"`, `"403 Forbidden"`, `"404 Not Found"`,
   `"422 Unprocessable"`, `"429 Rate Limited"`, `"Access Error"`, `"DNS Error"`,
   `"Connection Refused"`, `"Timed Out"`, `"SSL Error"`, `"Socket Error"`, `"… Server Error"`.
4. `app/.../widget/ForecastEvolutionRenderer.kt:122–123, 168, 175, 254, 271, 292` —
   `"Location actual"`, `"API actual"`, `"API actual: …"`, `"Location actual: …"`,
   `"Single High Forecast"`, `"Single Low Forecast"`.
5. `app/.../widget/PrecipitationGraphRenderer.kt:37` — `NOW_LABEL_TEXT = "NOW"`.
6. `app/.../widget/HourlyIndicatorRenderer.kt:14` — `NOW_TEXT = "NOW"`.

### B. Activity screens

7. `app/.../ui/WeatherObservationsActivity.kt:545` — `"… ${table.stationCount} stations"`.
8. `app/.../ui/WeatherObservationsActivity.kt:799` — `"No current observation fetch logs found for ${…}."`.
9. `app/.../ui/BugReportActivity.kt:209, 221` — `"No recent logs found in database."`,
   `"Logs excluded by user."` (plus the hardcoded Markdown report scaffold near line 230+).

### C. `:shared` prose consumed by Android (exactly the `95c87a92` class)

10. `shared/.../actuals/BlendTableFormatter.kt:53–55` — `COLUMN_HEADERS` (`"station"`, `"type"`,
    `"km"`, `"last read"`, `"age"`, `"raw"`, `"fed to blend"`, `"weight"`). Rendered by
    `WeatherObservationsActivity.kt:550–551`.
11. `shared/.../actuals/BlendTableFormatter.kt:102–106` — `LEGEND` (two explanation lines).
    Rendered by `WeatherObservationsActivity.kt:591`.
12. `shared/.../actuals/BlendTableFormatter.kt:159` — `"No blended points in range."`
    (`renderText`).
13. `shared/.../notify/DominantTempWatch.kt:45` — `"Dominant station temperature changed"`
    default. **Already handled** — Android overrides via `DominantTempChangeNotifier.kt:95`;
    the default serves desktop (no localization layer). Keep as a documented pattern.
14. `shared/.../util/NoHourlyChecker.kt:81–92` — `"No hourly forecast for …"`,
    `"Hourly data missing …"`, `"Results of refresh: …"`. **Desktop-only** (Android uses only
    `formatDayLabel`/`formatEndLabel`), so consistent with desktop having no localization layer.

### D. Debug / intentional

15. `app/.../widget/handlers/TemperatureTouchTargets.kt:575` — `"Dead zone tapped"` toast.
    Gated by `BuildConfig.DEBUG`; release leaves the message blank. Low priority.

### E. Desktop English (expected — no localization layer)

16. `desktop/.../CloudCoverGraph.kt:417` and the many Compose `Text("…")` literals across
    `desktop/`. By design, not in scope for this guard.

## Is a test possible? (feasibility conclusion)

Yes, but not a single perfect test. Key evidence from prototyping a naive detector:

- A global "≥2-word prose literal" heuristic over `app` + `shared` yields **~700 candidates**,
  dominated by log messages, KDoc-style comment strings, SQL strings, and weather-condition
  name mappings. A raw heuristic is unusable without a huge allowlist.
- A **sink-based** scan (literals passed directly to `drawText`/`setText`/`Toast`/notification
  builders) is precise but misses this codebase's common pattern of building prose in a helper and
  returning it (`buildMissingDiagnosticText`, `humanReadableErrorCode`, `BlendTableFormatter`).

So the guard is three complementary mechanisms:

1. **Android Lint `HardcodedText`** (built-in) — catches `setText("…")`, `Toast.makeText(…, "…")`,
   XML `android:text="…"`. Currently only `MissingTranslation`/`ExtraTranslation` are errors
   (`app/build.gradle.kts:210–216`). Does **not** catch `canvas.drawText` or `:shared` prose.
2. **Custom source-scanning JUnit test** (the main deliverable) — sink-based for `:app`, curated
   allowlist for `:shared`. Pure JVM, runs in the `Localization` bucket like
   `LocaleResourceParityTest`.
3. **Targeted Robolectric localization regression tests** (the `95c87a92` pattern) — per-string,
   strongest guarantee but only guards strings already known; reserved for the strings fixed in
   Phase 2.

## Proposed changes

### Phase 1 — the detector test (no translations required)

New file `app/src/test/java/com/weatherwidget/util/HardcodedUserFacingStringTest.kt`
(pure JVM, `@Category(Localization::class)`, follows `LocaleResourceParityTest`'s
`src/main` vs `app/src/main` working-dir resolution).

Two checks:

- **Check A — `:app` user-facing sinks.** Scan `app/src/main/java/**/*.kt`. Flag string literals
  (containing at least one `[a-z]` letter, so `"--°"`/`""` placeholders are exempt) passed directly
  to: `canvas.drawText(`, `.setText(`, `.setTextViewText(`, `.text =`, `Toast.makeText(`,
  `putExtra(…EXTRA_TOAST_MESSAGE…,`, `.setContentTitle(`, `.setContentText(`. Allowlist the current
  occurrences (A/B above) with a reason until Phase 2 fixes them.
- **Check B — `:shared` prose leak.** Scan `shared/src/main/kotlin/**/*.kt` for prose-like literals
  in the known user-facing surfaces (`graph/`, `notify/`, `actuals/BlendTableFormatter`,
  `util/NoHourlyChecker`, `stats/`), against a curated allowlist of the current entries (C above).
  This is the imperfect part (heuristic), which is why it is scoped narrowly and allowlisted.

**Prove-it-can-fail**: un-allowlist one current literal (or add a temporary literal at a sink) and
confirm the test goes red before trusting it.

**Category wiring**: a `Localization`-categorized class is automatically legal (the
`unitTestCategoryBuckets` map in `app/build.gradle.kts` already includes it), and runs via
`./gradlew :app:testLocalizationDebugUnitTest` / `./scripts/unit-tests.sh Localization`.

### Phase 2 — localize the occurrences (requires translation)

Fix items A and B (and optionally C's `BlendTableFormatter`) into base + 19 locale files, following
`95c87a92`'s exact pattern. Reuse where a localized resource already exists:
- `legend_actual` ("API actual"), `legend_location_actual` ("Location actual"),
  `label_location_actual` ("Location actual:") already exist and are translated in all 19 locales —
  `ForecastEvolutionRenderer` can adopt them with **zero new translations**.
- The rest (cloud diagnostic, watermark, `"NOW"`, evolution titles, bug-report strings,
  `BlendTableFormatter` headers/legend) need **new translations across 19 locales** — a significant
  mechanical + linguistic effort. Do these as a dedicated follow-up (translation workflow), not
  folded into the test change.

For each fixed string, add a targeted Robolectric locale regression test (the `95c87a92` pattern)
and remove the string from the scanner allowlist.

### Phase 3 — lint (investigate noise, then gate)

Evaluate `lint { error += "HardcodedText" }` in `app/build.gradle.kts`. Run `./gradlew :app:lintDebug`
first to size the existing-violation set; if noisy, land as `warning` with a baseline and promote to
`error` once Phase 2 clears the sink literals it can see. Lint's `HardcodedText` will never catch
`canvas.drawText`/`:shared`, which is why the custom test remains primary.

## Verification

1. `./gradlew :app:testLocalizationDebugUnitTest` — new test green; temporarily un-allowlist to prove
   it can fail.
2. `./scripts/unit-tests.sh` (or `:app:testByDurationDebugUnitTest`) — no category violations, suite
   still green.
3. Phase 2 (later): under a non-English locale (e.g. German), inspect an emulator screenshot of the
   cloud/watermark/evolution views and confirm localized text, per the evidence-first protocol.

## Schema impact

None. Source, test, and (Phase 2) string resources only.
