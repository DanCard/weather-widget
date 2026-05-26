# Hourly Footer Reorder: `<hour><icon><a|p>` → `<hour><a|p><icon>`

## Summary

Reorder the inline hourly graph footer from `<hour><icon><a|p>` to `<hour><a|p><icon>`.
Draw hour+meridiem as a single centered text string, then place the icon to the right.

## Changes

### GraphRenderUtils.kt (lines 285-300)

Replace the 3-draw layout block with:
- Concatenate `hourText + meridiem` into one string
- Draw centered on marker tick
- Place icon to the right of the text group
- Skip icon on last labeled hour if it would overflow right edge

### Comment updates (6 files)

- `GraphRenderUtils.kt:236` — doc comment
- `GraphLayout.kt:12`, `:116` — inline comments
- `WidgetFormatUtils.kt:9` — doc comment
- `CloudCoverGraphLabelPlacementRobolectricTest.kt:25` — test comment

## Verification

- Build: `./gradlew assembleDebug`
- Visual: install on emulator, resize widget to 2+ rows, check hourly footer markers
