# Enlarge API Touch Target Across Widget Views

## Prompt
Daily forecast view: Sometimes when I click on the api button, I get temperature graph instead.
I'm wondering if it would be easy to enlarge the api touch area to include the graph below.
Would be good if this is done on all views, in other words: make it consistent.

## Summary
- Add a dedicated top-right API touch overlay in the shared widget layout.
- Bind the same API toggle action to the label container and the new overlay in daily, temperature, precipitation, and cloud-cover views.
- Use a shallow top-right band so near-miss taps switch API without taking over most graph interactions.

## Key Changes
- Update `app/src/main/res/layout/widget_weather.xml` to add an invisible `api_touch_zone` positioned under the existing API label area and clear of the settings target.
- Keep `api_source_container` clickable and assign the same `PendingIntent` to both the label container and `api_touch_zone`.
- Apply the same API-toggle binding in:
  - `DailyViewHandler`
  - `TemperatureViewHandler`
  - `PrecipViewHandler`
  - `CloudCoverViewHandler`
- Preserve all other existing touch targets and graph interactions outside the new top-right band.

## Public Interfaces
- No public API, storage, or schema changes.
- Internal widget layout contract gains a new shared view ID: `api_touch_zone`.

## Test Plan
- Manual verification in daily graph view:
  - Tap the API label directly.
  - Tap just below the API label in the top-right graph strip.
  - Confirm both toggle API instead of triggering graph/day navigation.
- Manual verification in temperature, precipitation, and cloud-cover graph views:
  - Confirm the same top-right strip toggles API.
  - Confirm center and left graph taps still perform their existing actions.
- Regression checks:
  - Settings icon still opens settings.
  - Bottom graph row still triggers its existing action.
  - Text-only layouts still toggle API from the header area.

## Assumptions
- The enlarged target should be a constrained header-plus-top-strip band, not the full right side.
- “All views” means all widget modes that show the API indicator.
