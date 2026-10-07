# Forecast-only sources default to NWS actuals inside NWS coverage

User's decision (2026-10-07): change Google's default actuals provider from METAR to NWS in the US.
Applied to every borrowing source (Google Weather, Silurian), not Google alone.

## Why

- Same truth for every source. NWS forecasts are scored against NWS station observations, while
  borrowers were scored against METAR. Comparing their accuracy mixed forecast error with feed
  difference.
- Denser, closer, more frequent data: at Mountain View, AW020 is 2.2 km away against KNUQ's 3.7 km,
  and the 5-minute readings fill the gaps between whole-°C hourly METARs.
- Usually free: observation rows are per site, so NWS rows fetched for NWS itself are reused
  (`ActualsFeedPolicy`, plans/261007-desktop-borrowed-nws-actuals-not-fetched-on-full-refresh.md).

## Rule

`ActualsProviderResolver.defaultProviderFor(borrower)` = NWS when the active location is inside
`NwsCoverage`, otherwise METAR (and METAR when no location is known). It is derived on every read
and never stored, like `SourceCoverage.effectiveSources`. An explicit picker choice always wins.
Absent preference entries already meant "follow the default" on both platforms, so users who never
chose a provider move to NWS, and explicit choices are untouched.

## Design

- Add a location seam to `ActualsProviderResolver`, next to `installPreferenceSource`:
  `installLocationSource(() -> Pair<Double, Double>?)`. Around 30 call sites (several inside pure
  `:shared` blend code) ask `providerIdFor`; threading a location through all of them is the same
  problem the preference seam solved.
  - Android: `ActiveLocationResolver.current(context)`, which is SharedPreferences only, the same
    cost as the existing preference lookup.
  - Desktop: a volatile snapshot of `config.lat/lon` in `DesktopActualsPreference`, published at the
    places that already publish settings.
- `providerIdFor` evaluates the location only in the borrowing branch.
- `ActualsFeedPolicy.feedFor` passes its explicit lat/lon.
- `candidates()` lists the location's default first. The pickers on both platforms already read
  `defaultProviderFor`, so "store nothing for the default" stays correct.
- METAR keeps the name `DEFAULT_PROVIDER`, documented as the worldwide default outside NWS coverage.

## History

No migration. Stored `daily_history` values stay as computed. The observation window (10 days) is
recomputed from NWS rows on the next extremes pass, which for those days is the intended feed.

## Tests

- Resolver: a US location gives NWS; abroad, or with no location, gives METAR; an explicit
  preference wins; non-borrowers are unaffected; `candidates()` lists the default first.
- `ActualsFeedPolicy`: Google with no preference in the US needs no METAR fetch. `MetarFetchPolicy`
  consumers in the US no longer include Google or Silurian.
- Desktop: `DesktopActualsPreference` publishes the location.
- Full shared, desktop and Android unit suites.
