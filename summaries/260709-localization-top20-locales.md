# Localization: expand to top-20 languages

**Date:** 2026-07-09

## Goal

Extend the existing es/fr/uk localization (see `260708-localization-strings-es-fr-uk.md`) to the
top 20 languages by total speakers.

## Locales added (16 new, 20 total)

| Language | Resource dir | locale-config name | Notes |
|----------|--------------|--------------------|-------|
| Chinese (Simplified) | `values-zh-rCN` | `zh-CN` | Region-qualified so Traditional-script (zh-TW) devices fall back to English rather than getting Simplified |
| Hindi | `values-hi` | `hi` | |
| Arabic | `values-ar` | `ar` | RTL — `supportsRtl="true"` already set |
| Bengali | `values-bn` | `bn` | |
| Portuguese | `values-pt` | `pt` | Bare `pt` (BR-leaning wording) so both pt-BR and pt-PT devices match |
| Russian | `values-ru` | `ru` | |
| Urdu | `values-ur` | `ur` | RTL |
| Indonesian | `values-in` | `in` | Folder must use Android's legacy ISO code `in`, not `id` |
| German | `values-de` | `de` | |
| Japanese | `values-ja` | `ja` | |
| Turkish | `values-tr` | `tr` | Suffix apostrophes (`Widget\'ı`) escaped |
| Korean | `values-ko` | `ko` | |
| Vietnamese | `values-vi` | `vi` | |
| Italian | `values-it` | `it` | |
| Thai | `values-th` | `th` | |
| Polish | `values-pl` | `pl` | |

Existing: en (base), es, fr, uk.

## Conventions carried over from the es/fr/uk pass

- 250 translatable strings per locale; the 5 `translatable="false"` base strings omitted.
- `formatted="false"` mirrored on the three `personal_stations_*` strings.
- Quoted-string treatment preserved for leading/trailing-space strings (`forecast_history`,
  `obs_reported_prefix`, `obs_fetched_separator`, `bias_*_suffix`, `accuracy_*_line`).
- `%%` literal percent kept in `accuracy_within_line` (moved position where grammar required,
  count verified).
- Brand names (NWS, Open-Meteo, Silurian.ai, Tomorrow.io, Visual Crossing, WeatherAPI,
  Firebase Crashlytics) untranslated; `"Weather Widget"` in the add-widget how-to left in
  English (matches the launcher's widget-picker entry); launcher label (`app_name`) translated.
- `today` label chosen for widget column width per language (Heute/Hoje/今日/오늘/আজ/آج/…).

## Verification

- Parity script (scratchpad `verify_locales.py`): all 19 locale files = 250/250 keys, zero
  positional-format-arg mismatches, zero `%%`-count mismatches, `formatted=` attributes match.
- `:app:processDebugResources` passes (aapt validates XML escaping across all locales).

## Not done / future

- No native-speaker review — machine-quality translations; pseudolocale QA
  (`pseudoLocalesEnabled true`) still recommended before store rollout.
- Desktop app remains English (doesn't read Android resources).
- Play Store listing localization is separate from app localization.
