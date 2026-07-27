# Desktop Settings Parity With Android

Make the desktop Settings window (`SettingsWindow.kt`) look and function like
the Android `SettingsActivity`. The desktop currently wears Material 3's stock
`darkColorScheme()` purple, lacks several functional affordances Android has,
and duplicates logic that belongs in `:shared`.

## Evidence — current state

### Color/style (the "bad" part)

1. **Desktop theme is stock Material 3 purple with zero customization.**
   `desktop/src/main/kotlin/com/weatherwidget/desktop/SettingsWindow.kt:38` —
   `MaterialTheme(colorScheme = darkColorScheme())`. Every section title is
   rendered in M3's default `primary = #BB86FC` (the universal "I haven't
   themed my app yet" purple): `SettingsWindow.kt:90, 102, 114, 126, 133,
   155, 178`.

2. **Android already has a deliberate Apple-dark palette.**
   `app/src/main/res/values/colors.xml`:
   - `background #1C1C1E`, `surface_card #2A2A2E`, `surface_card_stroke #3A3A3E`
   - `widget_text_primary #FFFFFF`, `widget_text_secondary #AAAAAA`
   - Button palette (in `drawable/rounded_button_*.xml`): green `#4CD964`,
     blue `#5AC8FA`, navy `#0D2B45`, yellow `#FFCC00`/`#FFD60A`.

3. **Theme is reapplied inconsistently across desktop windows.**
   Each window sets its own `MaterialTheme(darkColorScheme())` inside its body
   (`SettingsWindow.kt:38`, `LocationPicker.kt:102`, `AppLogsWindow.kt:75`,
   `Main.kt:160`). `StatisticsWindow.kt:71` and `ForecastHistoryWindow.kt:142`
   don't wrap in `MaterialTheme` at all and inherit whatever the outer scope
   provides.

4. **Pervasive hardcoded `Color(0xFF...)` bypassing the theme.**
   `SettingsWindow.kt:298` `Color.Gray` for source descriptions;
   `StatisticsWindow.kt:191-201` Material 2 green/amber/red;
   `ObservationsWindow.kt:52-61` an entire local palette. None of these come
   from `MaterialTheme.colorScheme`.

5. **No card layout.** Android groups every section in a rounded card
   (`drawable/bg_surface_card.xml`, 14dp corner, 1dp stroke, 16dp padding).
   Desktop lays out flat against the background — sections are visually
   indistinguishable.

### Functional gaps (Android has, desktop lacks)

| # | Gap | Android location | Desktop status |
|---|---|---|---|
| 1 | "Get API Key" buttons per source | `item_api_key.xml`; `ApiKeySignupUrls.kt:24` | None — text field only (`SettingsWindow.kt:339-369`) |
| 2 | Hidden source dimming (`alpha=0.5f`) | `SettingsActivity.kt:284` | All rows full opacity |
| 3 | Click-on-text toggles checkbox | `SettingsActivity.kt:287-290` | Checkbox-only |
| 4 | "Keep at least one source" toast | `SettingsActivity.kt:302-307` (`must_keep_one_source`) | Silently refuses (`SettingsWindow.kt:286-287`) |
| 5 | Reverse-geocoded location label | `SettingsActivity.kt:423-431` via `LocationUpdater` | Shows raw `config.label` (`SettingsWindow.kt:141`) |
| 6 | Color-coded action buttons | `rounded_button_{green,blue,navy,yellow}.xml` | All buttons default M3 `primary` |
| 7 | Typography scale (18/16/15/14/12sp) | inline in XML | Default M3 type scale |
| 8 | Submit Bug Report button | `SettingsActivity.kt:132-136` → `BugReportActivity` | None |
| 9 | Source descriptions localized | `strings.xml:48-53` | Hardcoded English (`SettingsWindow.kt:408-416`) |
| 10 | Instant-apply (no Save required) | `TextWatcher`, `setOnSeekBarChangeListener` | Mandatory Save button (`SettingsWindow.kt:199-204`) |

### Desktop-only (intentional, keep)

- Exit-app button (only quit path with `WEATHER_DESKTOP_NO_TRAY`) — keep.
- Inline Icon Gallery (Android uses a separate activity) — keep.
- Diagnostics → Stations/Observations button — keep.
- Mandatory Save + `.config-changed` trigger file → daemon reload — keep the
  trigger mechanism (needed for the daemon-process architecture), but see
  Open Decision #1 below about per-field instant-apply.

### Code duplication suitable for `:shared`

AGENTS.md is strict: `:shared` is pure-JVM; no Compose, no Android `Context`,
no Room. The following are pure data/logic and can move there cleanly:

| Concern | Android today | Desktop today |
|---|---|---|
| API-key signup URLs | `app/.../ApiKeySignupUrls.kt` | (missing) |
| Source display order list (visible + hidden) | `SettingsActivity.kt:194-202` | `SettingsWindow.kt:257-264` |
| Source descriptions | `strings.xml:48-53` (localized) | `SettingsWindow.kt:408-416` (English) |
| Reorder logic (`swap(pos, pos±1)`, min-1 guard) | `SettingsActivity.kt:255-341` | `SettingsWindow.kt:266-336` |
| Color tokens | `colors.xml` | `darkColorScheme()` defaults |
| Default personal-station discount `95` | `WidgetStateManager.kt:66` | `DesktopConfig.kt:42` |

## Goals

1. Desktop Settings looks like Android Settings: same dark card-based layout,
   same color-coded buttons, same typography rhythm.
2. Desktop Settings functions like Android Settings: closes all 10
   functional gaps above (subject to Open Decisions).
3. The color palette and any pure-JVM settings logic live in `:shared`, so a
   future Android-Compose port (or any other JVM client) can reuse them
   verbatim. Both platforms stop hardcoding the same constants.
4. No regression to the existing `.config-changed` → daemon reload chain or
   to `WeatherWidgetWorker` enqueue policies on Android.

## Non-goals

- Porting Android `SettingsActivity` to Compose. Android stays on View XML.
  (No Compose dependency in `:app` — verified.)
- Sharing actual UI widgets/composables. Android (Views) and desktop
  (Compose) can't share widget code without unifying toolkits. Only data,
  tokens, and pure logic move to `:shared`.
- Adding new settings that exist on neither platform.
- Replacing the Dorkbox tray or genmon panel applet.
- Touching `LocationPicker.kt` beyond passing the existing
   `SharedLocationResolver` through for label enrichment.

## Design

### Phase 1 — Shared color tokens (fixes "bad colors" at the root)

**New file:** `shared/src/main/kotlin/com/weatherwidget/shared/util/WeatherThemeTokens.kt`

Pure-JVM constants (no Compose, no Android). Use 0xAARRGGBB `Long`/`Int` like
the existing `WeatherColors.kt:9-66`:

```
object WeatherThemeTokens {
    const val BACKGROUND = 0xFF1C1C1E
    const val SURFACE = 0xFF2A2A2E
    const val SURFACE_STROKE = 0xFF3A3A3E
    const val ON_SURFACE = 0xFFFFFFFF
    const val ON_SURFACE_SECONDARY = 0xFFAAAAAA
    const val PRIMARY = 0xFF007AFF       // iOS blue (theme accent)
    const val BUTTON_GREEN = 0xFF4CD964  // primary "go" actions
    const val BUTTON_BLUE = 0xFF5AC8FA   // secondary actions (logs, gallery)
    const val BUTTON_NAVY = 0xFF0D2B45   // tertiary (get-key, language)
    const val BUTTON_YELLOW = 0xFFCC00   // bug report
    const val ON_PRIMARY_DARK = 0xFF1A1A1A  // dark text on green/yellow
    // Typography scale (sp) — for parity reference; platforms apply natively.
    const val TITLE_SP = 18
    const val SECTION_HEADER_SP = 16
    const val BODY_SP = 15
    const val BODY_BOLD_SP = 15
    const val CAPTION_SP = 14
    const val SMALL_CAP_SP = 12
    // Shape/spacing — dp.
    const val CARD_CORNER_DP = 14
    const val BUTTON_CORNER_DP = 12
    const val CARD_PADDING_DP = 16
    const val SECTION_GAP_DP = 24
}
```

Also add `WeatherSource.description()` as an extension or member, replacing
both `strings.xml:48-53` (kept for backward compat) and the desktop's
`sourceDescription()` (`SettingsWindow.kt:408-416`). Default English; Android
can keep its localized `strings.xml` overrides.

**New file:** `shared/src/main/kotlin/com/weatherwidget/shared/util/ApiKeySignupUrls.kt`

Verbatim move of `app/src/main/java/com/weatherwidget/ui/ApiKeySignupUrls.kt:13-34`
into `com.weatherwidget.shared.util`. Android imports the relocated object;
desktop imports it too. Already-deleted Android file: replace with a thin
deprecated typealias for one release if external callers exist (none found).

**New file:** `shared/src/main/kotlin/com/weatherwidget/shared/util/WeatherSourceOrdering.kt`

Pure logic — extracted from both `SettingsActivity.kt:255-341` and
`SettingsWindow.kt:266-336`:

```
object WeatherSourceOrdering {
    val ALL_CONFIGURABLE: List<WeatherSource> = listOf(
        NWS, TOMORROW_IO, OPEN_METEO, SILURIAN, WEATHER_API, VISUAL_CROSSING,
    )
    val DEFAULT_VISIBLE: List<String> = listOf("NWS", "OPEN_METEO", "SILURIAN")

    fun ordered(visibleIds: List<String>): List<WeatherSource> { ... }
    fun toggleVisible(visibleIds: List<String>, source: WeatherSource): List<String>?
        // returns null if the toggle would empty the list (the "must keep one" case)
    fun moveUp(visibleIds: List<String>, source: WeatherSource): List<String>
    fun moveDown(visibleIds: List<String>, source: WeatherSource): List<String>
}
```

`toggleVisible` returning `null` for the illegal-empty case lets both UIs show
the same feedback (toast/snackbar) without duplicating the guard.

### Phase 2 — Desktop theme (visual parity)

**New file:** `desktop/src/main/kotlin/com/weatherwidget/desktop/theme/WeatherColors.kt`

Converts `WeatherThemeTokens` into a Compose `ColorScheme`:

```
val WeatherDarkColorScheme = darkColorScheme(
    primary = Color(WeatherThemeTokens.PRIMARY),
    background = Color(WeatherThemeTokens.BACKGROUND),
    surface = Color(WeatherThemeTokens.SURFACE),
    onSurface = Color(WeatherThemeTokens.ON_SURFACE),
    onSurfaceVariant = Color(WeatherThemeTokens.ON_SURFACE_SECONDARY),
    onBackground = Color(WeatherThemeTokens.ON_SURFACE),
    outline = Color(WeatherThemeTokens.SURFACE_STROKE),
)
val WeatherTypography = Typography(
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodySmall = TextStyle(fontSize = 12.sp, color = ON_SURFACE_SECONDARY),
    labelLarge = TextStyle(fontSize = 14.sp),
    ...
)
```

**New file:** `desktop/src/main/kotlin/com/weatherwidget/desktop/theme/WeatherButtons.kt`

Reusable `@Composable fun ActionButton(text, color, textColor, onClick)` and
named wrappers (`PrimaryActionButton = green`, `SecondaryActionButton = blue`,
`TertiaryActionButton = navy`, `AlertActionButton = yellow`). These replace
the bare `Button(...)` calls and give the desktop the Android button palette
without each call site restating the color.

**Edit:** All four desktop windows that set their own theme
(`SettingsWindow.kt:38`, `LocationPicker.kt:102`, `AppLogsWindow.kt:75`,
`Main.kt:160`) swap `darkColorScheme()` → `WeatherDarkColorScheme` and pass
`typography = WeatherTypography`. The two windows that don't currently wrap in
`MaterialTheme` (`StatisticsWindow.kt:71`, `ForecastHistoryWindow.kt:142`)
get wrapped too. Single-line change per site; everything downstream becomes
theme-aware automatically.

### Phase 3 — Card-based section layout

**New file:** `desktop/src/main/kotlin/com/weatherwidget/desktop/theme/SettingsCard.kt`

```
@Composable fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(WeatherThemeTokens.CARD_CORNER_DP.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().padding(bottom = SECTION_GAP.dp),
    ) {
        Column(Modifier.padding(CARD_PADDING.dp)) {
            Text(title, style = titleMedium)
            Spacer(8.dp)
            content()
        }
    }
}
```

**Edit:** `SettingsWindow.kt:83-186` — wrap each section (API Sources,
Personal Stations, API Keys, Icon Gallery, Location, Units, Diagnostics) in
`SettingsCard(...)`. Drop the per-section `Text(...titleMedium, color =
primary)` + `Spacer(8.dp)` boilerplate — it moves inside `SettingsCard`. The
section `Spacer(Modifier.height(24.dp))` between sections also moves inside
the card's bottom padding.

Net effect: the body column becomes 7 `SettingsCard` calls instead of 7
(title text + content + spacer) triplets. ~120 lines collapse to ~50.

### Phase 4 — Functional parity

Each gap from the evidence table, in implementation order (cheapest, biggest
win first):

1. **Header bar restyle** — `SettingsWindow.kt:42-81`. Back arrow stays.
   "Settings" title gets `titleLarge`. "Refresh Data" → `PrimaryActionButton`
   (green, dark text). "View App Logs" → `SecondaryActionButton` (blue).
   Wiring unchanged.

2. **API key "Get key…" buttons** — `ApiKeysList` (`SettingsWindow.kt:339-369`).
   Add `TertiaryActionButton("Get key…")` per row using
   `ApiKeySignupUrls.signupUrl(source)` from `:shared`. Open via
   `java.awt.Desktop.browse(URI)` (already JDK-only, no Swing dependency).

3. **Hidden-source dimming + click-on-text toggle + "must keep one" feedback**
   — `ApiSourcesList` (`SettingsWindow.kt:252-337`). Use
   `WeatherSourceOrdering` from Phase 1. Apply `Modifier.alpha(if (isVisible)
   1f else 0.5f)` to each row. Make the `Text` column `clickable` to toggle
   the checkbox. When `toggleVisible` returns null, show a Snackbar
   (`SnackbarHostState`) — Android's toast equivalent. Hide up/down arrows
   for hidden rows (`SettingsActivity.kt` sets them `INVISIBLE`).

4. **Reverse-geocoded location label** — `SettingsWindow.kt:132-150`. The
   desktop already has `SharedLocationResolver` in scope (`Main.kt:198-201`).
   Pass it into `SettingsWindow` as a parameter; add a `LaunchedEffect` that
   calls `resolver.describeCurrentLocationResolved(lat, lon)` and updates a
   `var locationLabel by remember { mutableStateOf(config.label) }`. Reuse
   the Android method name/path. Don't block the UI thread — the resolver is
   already a suspend function.

5. **Submit Bug Report button** — new section after Diagnostics. Desktop has
   no `BugReportActivity`, but the daemon already emits a diagnostic log
   (AppLogs). MVP: a button that opens `AppLogsWindow` with a
   "Copy diagnostics to clipboard" affordance + a `mailto:` link. Full
   Android-parity bug-report form (description field, checkboxes) can be a
   follow-up — flagged in Open Decision #2.

6. **Typography pass** — implicit after Phase 2; just verify the rendered
   sizes match Android's 18/16/15/14/12 sp scale by screenshot diff.

### Phase 5 — Apply vs Save (Open Decision #1)

The Android screen applies every field instantly. The desktop requires a
Save press because the daemon-process architecture relies on the
`.config-changed` trigger file (`DaemonProcess.kt:628-656`) to know when to
reload. Recommend:

- **Keep Save** as the commit action (it's the only thing that touches the
  `.config-changed` trigger).
- **Add dirty-state indication**: the Save button shows "Save (unsaved)" or
  gets an asterisk when `currentConfig != config`. Disable window close (or
  show a confirm dialog) when dirty.
- **Auto-save on timeout**: an idle timer (e.g. 5s after the last edit)
   writes config silently, so users who close without Save don't lose work.

This matches the desktop's daemon architecture without forcing the user to
remember to save.

## Phased delivery

Each phase is independently mergeable. Verify by screenshot diff against
Android after each phase.

| Phase | Scope | Files touched | Verify |
|---|---|---|---|
| 1 | `:shared` tokens + ordering + signup URLs | 3 new in `:shared`, 1 deleted in `:app`, 2 edited | `:shared:test`, `:app:test`, `ApiKeySignupUrlLivenessTest` still passes |
| 2 | Desktop theme + buttons | 2 new in `:desktop/theme`, 5 edited (the 5 window roots) | Screenshot: section titles no longer purple |
| 3 | Card layout | 1 new in `:desktop/theme`, 1 edited (`SettingsWindow.kt`) | Screenshot: sections look like Android cards |
| 4 | Functional gaps (5 items above) | `SettingsWindow.kt` + `Main.kt` parameter plumbing | Manual run-through of each gap; robolectric/instrumented tests for `WeatherSourceOrdering` |
| 5 | Save UX | `SettingsWindow.kt` | Manual dirty/clean state test |

## Testing strategy

### Pure-logic (Phase 1) — `:shared`, plain JUnit

`shared/src/test/kotlin/com/weatherwidget/shared/util/`:
- `WeatherSourceOrderingTest` — `toggleVisible` returns null on the
  last-source case; `moveUp`/`moveDown` no-op at boundaries; `ordered`
  returns visible (in order) then hidden (in canonical order).
- `ApiKeySignupUrlsTest` — every source in `sourcesRequiringKeys` returns a
  non-empty https URL. Existing `ApiKeySignupUrlLivenessTest` (Android)
  should be moved/ported to `:shared` so the liveness check runs in CI for
  both clients.
- `WeatherThemeTokensTest` — sanity: every token is a valid ARGB long, the
  button colors differ from M3 purple `0xFFBB86FC`.

All Short-duration `@Category(ShortDuration::class)` per AGENTS.md.

### Desktop tests (Phase 2-5) — Robolectric is NOT used on `:desktop`

Desktop uses Compose for Desktop; tests run as plain JUnit on the JVM via
`ui-test-junit4` (`desktop/build.gradle.kts:91`). Bucket as
`ShortDuration`/`MediumDuration` per AGENTS.md.

- `SettingsWindowTest` — Compose UI test (already used in `:desktop`):
  - `ApiSourcesList` renders all 6 configurable sources at the right alpha
    for visible vs hidden.
  - Tapping the source name toggles the checkbox.
  - Unchecking the last visible source shows a Snackbar with "must keep one".
  - "Get key…" button per source opens the expected URL (assert
    `Desktop.browse` is invoked via a fake).
  - Save button reflects dirty state; auto-save timer fires after idle.
- `WeatherDarkColorSchemeTest` — section title color equals
  `WeatherThemeTokens.PRIMARY`, not M3 purple.

### Manual verification (per AGENTS.md "Evidence-First Protocol")

After each phase, screenshot the desktop Settings window side-by-side with
the Android Settings screen on the emulator. Confirm visual match. Tag the
screenshot commit/diff in the session log.

## Open decisions

1. **Instant-apply vs Save.** Phase 5 above proposes a hybrid (dirty-state +
   auto-save timer). Alternative: full Android parity — every field writes
   `config.json` + touches `.config-changed` on change. That's heavier on
   the daemon (it restarts loops on every toggle) but matches Android
   exactly. Recommend the hybrid; confirm before Phase 5.

2. **Bug Report scope.** Phase 4 item 5 ships an MVP (open AppLogs +
   `mailto:`). Full Android `BugReportActivity` parity (description field +
   diagnostic checkboxes + share intent) is a separate plan. Confirm MVP is
   acceptable for this work.

3. **Localization.** Android has localized source descriptions in
   `strings.xml`; desktop is English-only. Phase 1 puts English in `:shared`
   as the default. If desktop l10n is wanted later, it needs its own
   resource bundle (no `:shared` resource support today). Out of scope here
   unless flagged.

4. **StatisticsWindow / ForecastHistoryWindow theme gap.** These two
   windows don't currently wrap in `MaterialTheme` at all
   (`StatisticsWindow.kt:71`, `ForecastHistoryWindow.kt:142`). Phase 2 fixes
   them as a side effect — but they're not in the Settings screen. Confirm
   we fix them in this work (cheap) vs a separate pass.

## Out-of-scope but noted

- Android uses `FlexboxLayout` for the header row; desktop `Row` is fine
  (already in place).
- Android's language-settings button (`SettingsActivity.kt:162-181`) has no
  desktop equivalent — system per-app locale is Android-only. Drop.
- Android's "Refresh Data" observes WorkManager completion
  (`SettingsActivity.kt:343-376`); desktop's "Refreshing…" suspend-call
  affordance (`SettingsWindow.kt:58-73`) is adequate parity.
