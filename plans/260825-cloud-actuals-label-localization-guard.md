# Localize the hourly cloud actuals-source label

## Evidence

Commit `44cc5fcc` added `Actual cloud cover data from <provider>` as a Kotlin string literal in
`DominantStationLabel.formatCloudSourceLabelText`. Android and desktop both consume that formatter,
so Android's resource pipeline never sees the new user-facing text.

`LocaleResourceParityTest` already requires every translatable base-resource key to exist in every
shipped locale. It could not catch this regression because no resource key was added.

## Changes

1. Add `actual_cloud_cover_data_from` with a `%1$s` provider placeholder to the base Android string
   resources and all 19 translated locale files.
2. Make `DominantStationLabel` wrap already-localized label text instead of constructing English
   prose in `:shared`.
3. Resolve the Android label through `Context.getString`; keep desktop's currently English-only text
   at the desktop platform boundary.
4. Add a Robolectric localization regression test that switches to German and verifies the graph
   label is German. Together with `LocaleResourceParityTest`, this guards both runtime resource use
   and translation completeness.

## Verification

1. Demonstrate the new behavior test fails against the hard-coded implementation.
2. Run the focused localization, shared label, and desktop test tasks after implementation.
3. Render the cloud-cover view under a non-English Android locale and inspect an emulator screenshot.

## Schema impact

None. This changes string resources, graph-label construction, and tests only.
