# 260907 — Remove "Show reading age" and caption the Today-overlay section

## User prompts

> On settings page: 1: Remove "show reading age" setting 2: Add text on the setting that only
> enabled if row height limit reached.

> "in graph view" text isn't helpful. proceed

> Lets move the description text above the setting toggles

> commit

## Change (dual-platform)

1. **Full removal of the reading-age feature** (not just the toggle):
   - Android: switch row removed from `activity_settings.xml`; wiring removed from
     `SettingsActivity.kt`; `showTodayOverlayDominantAge`/`setShowTodayOverlayDominantAge` and the
     pref key removed from `WeatherDisplayPreferences.kt` + `WidgetStateManager.kt`;
     `today_overlay_dominant_age_label` string deleted from the base and all 20 locale files.
   - Pipeline: `dominantAgeText` / `showDominantReadingAge` removed from
     `TodayColumnOverlayContentResolver` (shared), `TodayColumnOverlayBlocks` (degradation ladder is
     now delta → delta+temp), `TodayColumnOverlayRenderer`, `DailyGraphRenderer`, and
     `DailyForecastGraphRenderer`. The age row can no longer render anywhere.
   - Desktop: toggle removed from `SettingsWindow.kt`; `todayOverlayDominantAge` dropped from
     `DesktopConfig.kt` (previously-saved configs with the key are ignored); age plumbing removed
     from `DesktopDailyForecastModel.kt` + `DailyForecastGraph.kt`.
2. **Height caption**: new `today_overlay_dominant_temp_description` string — "Appears in the Today
   column only on widgets at least 4 rows tall." — translated into all 19 non-base locales
   (`LocaleResourceParityTest` enforces key parity; apostrophes escaped in uk/tr/fr). Rendered as a
   13sp secondary TextView on Android and a `bodySmall` Text in the desktop window, placed ABOVE the
   two toggles (user request) with a 12dp gap on Android.
3. Tests updated: `TodayColumnOverlayBlocksTest`, `TodayColumnOverlayContentResolverTest`,
   `TodayOverlaySettingsRoboTest`, `SettingsActivityRobolectricTest`, `DailyLargeTodayLayoutRoboTest`
   (age row dropped from expectations), `SettingsDraftRebaseTest`, `DesktopDailyForecastModelTest`
   (age-alone case removed).

## Verification

- `:shared:testByDurationShared`, `:app:testByDurationDebugUnitTest` (all buckets),
  `:app:testLocalizationDebugUnitTest`, `:desktop:testByDurationDesktop` all pass.
- Emulator (debug install): settings card shows caption above the two toggles, no age row; widget
  Today column still renders `-0.5 fcst / 69.8°` (KNUQ). Screenshots:
  `/tmp/wwdbg/emu_settings_desc_top.png`, `/tmp/wwdbg/emu_widget_final.png`.

## Notes

- Stale `show_today_overlay_dominant_age` booleans remaining in on-device `widget_state_prefs.xml`
  are simply ignored.
- The stale pref key row was removed from the code; no migration needed.
