# Product Guidelines - Weather Widget

## Design Principles
- **Information Density:** Maximize the amount of useful data shown without sacrificing legibility.
- **Battery First:** UI updates should be opportunistic; data fetches should be battery-aware.
- **Transparency:** Show users the source of the data and its historical accuracy.
- **Responsiveness:** Widget should feel fast; use cached data for immediate feedback.
- **Visual Continuity:** Use consistent colors and styles for graphs across all widget sizes.

## UI/UX Standards
- **Daily View:** Use graphical bars for temperature ranges.
- **Hourly View:** Use Bezier curves for temperature trends.
- **Accuracy Indicators:** Use color-coded dots (Green: ≤2°, Yellow: ≤5°, Red: >5°).
- **Navigation:** Arrow buttons for time shifting; tap API indicator to toggle source.

## Coding Standards
- **Idiomatic Kotlin:** Use coroutines, extensions, and modern Kotlin patterns.
- **Database Safety:** Ensure all Room queries are efficient and handled on background threads.
- **Widget Limitations:** Be mindful of `RemoteViews` limitations (memory, binder limits).
- **Documentation:** Maintain `ARCHITECTURE.md` and keep track of significant changes in `conductor/`.
