# Localization: string extraction + es/fr/uk translations

**Date:** 2026-07-08

## Goal

Move all hardcoded user-facing strings into `strings.xml` to enable localization, then ship
Spanish, French, and Ukrainian translations.

## Phase 1 — Extract hardcoded strings to resources

~60 new string resources; two treatments applied:

- **Static labels → `@string`**: screen titles/headers (Statistics, Current Observations,
  Forecast History), buttons (Close, Fetch Logs, Save), row labels (`Forecast:`, `Day (8a–8p):`),
  accessibility `contentDescription`s (Move up/down, Current Stations).
- **Runtime-replaced placeholders → `tools:text`**: adapter-bound values (`72° / 55°`, `NWS`,
  `Station Name`, dates). Design-time only; aapt strips them, so no untranslatable text ships.

Kotlin-composed text moved to `getString(...)` with positional args: toasts in
`ConfigActivity`/`WeatherObservationsActivity`/`BugReportActivity`, the Name Location dialog,
observation subtitles, accuracy summary builders in `StatisticsActivity` and
`ForecastHistoryActivity` (both `formatBias` helpers share `bias_low_suffix`/`bias_high_suffix`),
`LocationUpdater.describeCurrentLocation`, the app-logs status line, and `WidgetRenderer`'s
"Today"/"Loading..."/"Tap to refresh" states. Where C/F paths formatted numbers differently
(`%.1f` vs `%d`), the number is pre-formatted in Kotlin and passed as `%1$s` — unit logic stays
in code, only translatable words live in resources.

Dead resources `app_logs_count`/`app_logs_count_filtered` removed.

## Phase 2 — "Today" label through DailyViewLogic

`DailyViewLogic.prepareTextDays`/`prepareGraphDays` gained a **required** `todayLabel: String`
parameter (last position — production callers pass the first 16 args positionally). Required
with no default per the `useCelsius` rule: a `= "Today"` default would silently ship English at
any missed call site. Production callers (`DailyGraphRenderer.kt`, `DailyViewHandler.kt`) pass
`ctx.context.getString(R.string.today)`; all 88 test call sites across 10 files pass
`todayLabel = "Today"` (inserted mechanically, count verified). `DailyViewLogic` stays
Context-free for plain-JUnit tests.

## Phase 3 — Translations (es, fr, uk)

- `values-es/`, `values-fr/`, `values-uk/` `strings.xml` — 250 strings each.
- Five base strings marked `translatable="false"` (pure formats `%1$s`, `%1$s (%2$s)`, `?`, and
  log levels `VERBOSE`, `DEBUG+`) so lint's `MissingTranslation` stays meaningful.
- `res/xml/locales_config.xml` + `android:localeConfig` in the manifest → Android 13+ per-app
  language picker (en/es/fr/uk).
- Choices: fr "Today" = **"Auj."** (widget column width); uk = «Сьогодні» (wide — eyeball on
  2-column widget). Brand names untranslated; launcher label translated ("Widget del Clima" /
  "Widget Météo" / «Віджет погоди»). `formatted="false"` mirrored on the three
  `personal_stations_*` strings. French/Ukrainian apostrophes escaped (`\'`).

## Verification

- Key-parity script: base translatable keys == each locale's keys (250/250, no missing/extra).
- Format-arg script: positional specifiers match base in every string (0 mismatches) — a dropped
  `%2$s` is a runtime `MissingFormatArgumentException` in one language only, invisible to aapt.
- `:app:compileDebugUnitTestKotlin`, `:app:compileDebugAndroidTestKotlin`,
  `:app:processDebugResources`, `:app:processDebugMainManifest` all pass; affected unit test
  classes (DailyViewLogicTest, DailyViewHandlerTest, WeatherObservationsActivityRobolectricTest,
  ConfigActivityAddFlowRoboTest, etc.) green.

## Deliberate exclusions (future work)

- `ApiSourceWarningHelper` warning texts ("401 error", "API key missing.") — pure
  `@VisibleForTesting` classifier; localizing means threading Context through a testability seam.
- `widget_weather.xml` initial-layout dummies (`Mon`, `Day`, `--°`, `NWS`) — RemoteViews
  placeholders visible only pre-first-paint; every binder overwrites every view.
- **Desktop app** — doesn't read Android resources; the Linux port stays English.
- **Celsius is NOT locale-automatic**: `useCelsius()` = `prefs.getBoolean("use_celsius", false)`,
  so localized users still start in °F. Proposed (not implemented): when the pref has never been
  set, default from locale (Celsius except US/LR/MM); explicit toggle wins forever.

## Advice given on locale count

Start with few locales, not top-30: marginal cost is QA (overflow, RTL, plurals) not translation;
NWS/US gravity well; store-listing localization ≠ app localization. Pseudolocale testing
(`pseudoLocalesEnabled true`, `en-XA`) recommended before expanding.
