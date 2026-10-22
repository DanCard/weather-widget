# Actuals source picker: mark feeds that cannot serve here

User, 2026-10-21: "'Current observations': 'Actual source': Should we give non active sources,
derived sources as an option to choose from?"

## Current state

The picker already offers every source that can provide actuals, whether or not it is enabled or
displayed. Its list comes from `:shared` `ActualsProviderResolver.candidates()` (Android
`WeatherObservationsActivity.showActualsSourceChooser`, desktop `ObservationsWindow.ActualsSourceRow`):

| Offered | Tier | Label |
|---|---|---|
| NWS, METAR, Synoptic | `STATION_OBSERVATION` | measured |
| Open-Meteo, Tomorrow.io | `RECENT_ANALYSIS` | derived |
| WeatherAPI | `ARCHIVED_PROVIDER_HISTORY` | derived |
| Google, Silurian | — | not offered: they borrow actuals themselves |
| OpenWeatherMap, Visual Crossing | `NONE` | not offered: no history product |

The chosen feed is fetched even when that source is not enabled (`ActualsFeedPolicy.currentTempFeeds`:
"a borrower reaches its feed even when the feed is not itself visible"; `fullRefreshFetch` is
covered for every source × candidate in `ActualsFeedPolicyTest`).

**Answer to the question:** yes, and they already are. Keep it that way. Borrowing a feed is not
viewing it.

## Problem

`candidates()` ignores location and keys, so the picker offers choices that cannot deliver here:

1. **NWS outside `NwsCoverage`** (e.g. Kyiv). The choice is accepted and stored, then
   `ActualsFeedPolicy.feedFor` returns null. Nothing is fetched, and the actuals line is blank with
   no explanation.
2. **Tomorrow.io / WeatherAPI with no effective key** (none entered, none baked — public builds). The
   fetch fails on every attempt, and the actuals line is blank.
3. **Synoptic with no build-time token** (`SynopticApi.isConfigured == false`). The fetch fails with
   "synoptic: no token configured", and the actuals line is blank.

## Fix

### Rule (`:shared`, written once)

`ActualsProviderResolver`:

```kotlin
enum class Unavailable { OUTSIDE_COVERAGE, NO_KEY }

data class Candidate(
    val source: WeatherSource,
    val tier: Tier,
    val isDefault: Boolean,
    val unavailable: Unavailable?,   // null = usable here
)

/** The picker's rows. Same set and order as [candidates]; unusable ones are marked, not dropped. */
fun candidateOptions(
    defaultProvider: WeatherSource,
    location: Pair<Double, Double>? = installedLocation(),
    synopticConfigured: Boolean,
    hasKey: (WeatherSource) -> Boolean,
): List<Candidate>
```

- `OUTSIDE_COVERAGE`: NWS when the location is known and outside `NwsCoverage` —
  `servesHere(source, location)`, the one test that `feedFor` and `resolve` also use.
- `NO_KEY`: a `requiresApiKey` feed with `hasKey` false, or Synoptic with `synopticConfigured` false.
  The platform passes only its reads, inline at the call site (no platform function, so
  `CrossPlatformDuplicateNamesTest` stays clean — an `actualsSourceOptions` helper on each side
  tripped it):
  - Android: `BuiltInApiKeys.hasEffectiveKey`, `BuildConfig.SYNOPTIC_API_TOKEN`.
  - Desktop: `!needsKeyFromUser(...)`, `"SYNOPTIC"` in `DesktopApiKeys.DEFAULTS` / user keys.

  Keyless feeds (NWS, METAR, Open-Meteo) always pass.
- **Order is unchanged** (default first, measured before derived, then id). Unusable rows stay where
  they are, so the list does not move when the user travels. This is the same principle as Settings'
  "Not available at this location": derive on every read, never edit the list.
- The label strings come from `:shared` (`ActualsPickerLabels.row(candidate)`, `.value(readId, overridden)` →
  `"NWS  (measured) — not available at this location"`, `"… — needs API key"`), so the two
  platforms cannot drift.

`candidates()` stays as it is: it is what the resolver accepts, and `ActualsFeedPolicyTest` iterates it.

### Read side: no blank when the stored choice cannot serve here

`ActualsProviderResolver.resolve` currently keeps a preference whenever `canProvide(it)`. It gains
**`&& servesHere(it, location)`**, so a stored NWS choice abroad reads the borrower default (METAR).
The stored preference is **not** rewritten: back inside coverage, NWS returns. This follows
CLAUDE.md's rule that a location never edits the user's choices, and it is what makes `feedFor`'s
null case unreachable for a preference (it stays as a guard).

Keys are **not** checked on the read side. A key can be entered later, and the resolver has no key
seam. Adding a third installed lookup for this alone is not worth it; the picker gate is enough,
because a key cannot vanish without the user removing it. (Open question below.)

### Platforms (UI only)

- **Android** `showActualsSourceChooser`: `setSingleChoiceItems(labels, …)` cannot disable rows, so
  switch to an `ArrayAdapter` overriding `isEnabled(position)` and greying disabled rows. A tap on a
  disabled row does nothing.
- **Android** `updateActualsSourceRow`: the row's value shows what is *actually read*. A stored
  choice that does not serve here shows `"METAR (NWS not available here)"`.
- **Desktop** `ActualsSourceRow`: `DropdownMenuItem(enabled = candidate.unavailable == null)`, with
  `testTag("actuals_candidate_${id}")`. The row value is the same as on Android.

### Logging

`ACTUALS_PICKER_OPEN source=… unavailable=NWS:OUTSIDE_COVERAGE,TOMORROW_IO:NO_KEY` (INFO, once
per open, both platforms through `:shared` `Log`). When a stored preference is overridden by
coverage: `ACTUALS_PROVIDER_FALLBACK source=… chosen=NWS used=METAR reason=outside_coverage`, at
VERBOSE, because `resolve` is on the hot blend path.

## Tests

`:shared` (`ActualsProviderResolverTest`):
- Kyiv: NWS is `OUTSIDE_COVERAGE`. US: no row is unavailable when every key is present. Unknown
  location: NWS is usable (we cannot tell).
- `hasCredentials = { false }` marks Tomorrow.io, WeatherAPI and Synoptic `NO_KEY`, and never NWS,
  METAR or Open-Meteo.
- `candidateOptions().map { it.source } == candidates()` at every location (same set, same order).
- `resolve`: Google with preference NWS → `"NWS"` in the US, `"METAR"` in Kyiv, and the preference
  lookup is untouched. **This fails on the old code** (it returns `"NWS"` in Kyiv).
- `ActualsFeedPolicyTest`: in Kyiv, `feedFor(GOOGLE, NWS preference)` is now METAR, not null (update
  the existing `NWS feed outside coverage is no feed` test; it documents the old behaviour).

Android (Robolectric, `WeatherObservationsActivityRobolectricTest`): location set to Kyiv, open the
chooser, and the NWS row's adapter `isEnabled` is false. Clicking it leaves `getActualsProvider`
unchanged. The row value reads the fallback text.

Desktop (`ActualsSourcePickerTest`, Compose): Kyiv → `actuals_candidate_NWS` is not enabled.
With an empty key map and an empty `DEFAULTS` → `actuals_candidate_TOMORROW_IO` is not enabled.

Integration (2+ classes): `ActualsProviderResolver` + `ActualsFeedPolicy.currentTempFeeds` in Kyiv
with an NWS preference → the METAR fetch is planned (the station-network refresher's `borrowers`
includes the source).

## Out of scope

- Reclassifying OpenWeatherMap as `RECENT_ANALYSIS` to offer it as derived (see `tierOf` KDoc: it
  reaches `preservesHistoricalCloud` and the backfill gate).
- Offering Google / Silurian: they have no observation product.

## Open question

Should a stored keyed choice whose key was later removed also fall back on the read side? That
needs an installed key lookup in the resolver, like the existing preference and location lookups.
Proposed: no, until it happens. Removing a key is a deliberate act in Settings, and the picker then
shows the row as "needs API key".

## Done (2026-10-21)

- `:shared`: `servesHere`, `candidateOptions`, `overriddenChoice`, `logPickerOpen` in
  `ActualsProviderResolver`; `resolve` falls back abroad; `ActualsFeedPolicy.feedFor` uses `servesHere`;
  `ActualsPickerLabels`.
- Android: `ActualsOptionAdapter` (greyed, non-selectable rows; the click handler also ignores them,
  since `performItemClick` bypasses `isEnabled`).
- Desktop: `DropdownMenuItem(enabled = …)`, `testTag("actuals_candidate_<id>")`.
- Updated tests that pinned the old "abroad fetches nothing" behaviour:
  `NWS feed outside coverage is no feed…`, `borrowed NWS outside coverage fetches nothing`.
