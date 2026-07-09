# UnitDefaults: OS temperature-preference rung (Android 14 Regional Preferences)

**Date:** 2026-07-09
**Follows:** the useCelsius region-default design (es/fr/uk localization summary, Phase 4)

## Goal

Insert a middle rung in the unit-default ladder: **explicit in-app switch → explicit OS-level
temperature preference → region default**. Android 14's Regional Preferences let users tell
the OS "Celsius" or "Fahrenheit" explicitly (stored as the `-u-mu-` Unicode locale extension);
that's a stronger signal than region inference and should beat it — but only when actually set.

## Changes

- **`shared/.../UnitDefaults.kt`** — two new pure overloads (mapping logic stays in `:shared`,
  plain-JUnit testable, both platforms share it):
  - `defaultUseCelsius(explicitTemperatureUnit, countryCode)`: `"celsius"`/`"kelvin"` → true,
    `"fahrenhe"`/`"fahrenheit"` → false, absent/unknown → existing region logic. **CLDR
    truncates keyword values to 8 chars, so Fahrenheit arrives as `"fahrenhe"`** — matching
    the full spelling only would never fire. Kelvin maps to Celsius (nearer of the two scales
    offered).
  - `defaultUseCelsius(locale)`: reads `locale.getUnicodeLocaleType("mu")` — the JVM/desktop
    path.
- **`WidgetStateManager.useCelsius()`** — default path now reads region from
  `Resources.getSystem()` (unchanged; per-app language can't reach it) and the explicit unit
  via `LocalePreferences.getTemperatureUnit(context.resources.configuration.locales[0],
  resolved = false)` (androidx.core 1.12 already in the catalog; unresolved returns "" unless
  the user actually set the OS preference — resolved=true would replace our region logic with
  ICU's and lose the deliberate LR/MM Celsius choice). Added a permanent `UNIT_DEFAULT`
  logcat breadcrumb logging the full decision (osUnit/region/appLocale → result).
- **Desktop** — `DesktopConfig`, `PanelIpcServer`, `Main.kt` call sites switched to the
  `Locale` overload (`Locale.getDefault()`); on Linux the `mu` extension is virtually always
  absent, so behavior is unchanged but the ladder is shared.

## The gotcha (cost one debugging round)

**`Resources.getSystem()` does NOT carry the `-u-mu-` extension.** The OS merges Regional
Preferences into *app* configurations (dumpsys shows `[en_US_#u-mu-celsius]` in
mGlobalConfiguration and every activity config) while the bare system Resources locale stays
`en_US`. First implementation read the extension from `Resources.getSystem()` and silently
got "" forever. Fix: region from system Resources (per-app-language-proof), extension from
`context.resources.configuration.locales[0]` (where the OS actually delivers it). Failure
mode if the extension is missing there: falls through to region — the pre-change behavior.

Second red herring: the emulator had a **stale explicit `use_celsius=false`** in
`widget_state_prefs.xml` (old test artifact), so the default path never ran and no
`UNIT_DEFAULT` log appeared. Removed surgically via
`run-as com.weatherwidget sed -i '/use_celsius/d' shared_prefs/widget_state_prefs.xml`
(never `pm clear`). The absent log line was itself the diagnostic: no log = explicit-pref
branch taken.

## Verification

- `UnitDefaultsTest`: 6 new tests — precedence over region both directions, truncated +
  full + uppercase Fahrenheit spellings, Kelvin, fall-through (null/""/garbage), and the
  Locale overload parsing real `en-US-u-mu-celsius` / `de-DE-u-mu-fahrenhe` tags. Green.
- On emulator (API 36): Settings → System → Languages → Regional preferences → Temperature.
  - Set **Celsius** → `persist.sys.locale` became `en-US-u-mu-celsius`; app relaunch logged
    `osUnit='celsius' region=US appLocale=en-US-u-mu-celsius -> celsius=true`; Use Celsius
    switch rendered ON. US region overridden by explicit OS preference. ✓
  - Reset to **Use default** → logged `osUnit='' region=US appLocale=en-US -> celsius=false`;
    back to Fahrenheit. ✓
- `:app:ktlintCheck`, `:app:compileDebugKotlin`, `:desktop:compileKotlin` pass. Robolectric
  suites unaffected (default `en-rUS` has no extension → region path, still Fahrenheit).
- Emulator left clean: OS temperature pref reset to default, stale pref key removed
  (region default yields the same Fahrenheit), app locale unset.

## Behavior matrix (unset in-app switch)

| OS temp pref | Region | Result |
|---|---|---|
| Celsius | US | **Celsius** (new) |
| Fahrenheit | DE | **Fahrenheit** (new) |
| unset | US | Fahrenheit (unchanged) |
| unset | elsewhere/unknown | Celsius (unchanged) |

Explicit in-app switch still beats everything, forever.
