# Testing Strategy Notes

## Current Approach (Working Well)
- 10 test files, all pure unit tests — no Android dependencies, no mocking
- Tests cover: utility functions (interpolation, navigation, icons, sun position), API parsing, repository logic, weather gap handling
- Fast execution, simple setup, easy to understand

## Should We Add a Mocking Framework?

**Decision: No — prefer pure function extraction instead.**

### Reasoning

**Against mocking (MockK / Mockito):**
- Adds dependency complexity and build time (especially MockK with bytecode generation)
- Mocking Android platform classes (`AppWidgetManager`, `Context`) is brittle — API changes, OEM variations
- "Don't mock what you don't own" — mocking `AppWidgetManager` tests how we *think* it works, not how Samsung's or Pixel's launcher actually reports dimensions
- Sets a precedent where future tests over-mock instead of designing for testability
- The project is a focused widget app, not a large multi-module app where mocking pays for itself

**For mocking:**
- Could test more provider logic directly
- Industry standard practice
- Would enable testing repository interactions with DB mocks

### Preferred Pattern: Pure Function Extraction
When logic inside Android-coupled code is worth testing, extract it into a pure function:

```kotlin
// Instead of mocking AppWidgetManager to test getWidgetSize():
fun selectWidgetDimensions(
    minW: Int, minH: Int, maxW: Int, maxH: Int, isPortrait: Boolean
): Pair<Int, Int>

// Pure function, trivially testable, no dependencies
```

This gives the same logical coverage with zero new dependencies. The Android glue layer stays thin and untested (verified on-device instead).

### What Unit Tests CAN'T Verify Here
- Actual widget dimensions reported by different launchers/OEMs
- Visual rendering correctness (stretched graphs, label overlap)
- Real device behavior (Pixel 7 Pro's 19.5:9 screen ratio effects)

These require on-device testing, which no amount of mocking replaces.

## When to Reconsider
- If the app grows significantly beyond widget rendering
- If we add complex business logic with many code paths and Android dependencies
- If we start having regressions that pure function tests can't catch
