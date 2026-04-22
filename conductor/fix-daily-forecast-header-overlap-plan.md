# Objective
Fix the intermittent overlap between the Daily forecast view's high temperature label and the header on specific OEM devices (like Samsung).

# Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`

# Implementation Steps
1. In `DailyForecastGraphRenderer.kt`, update `TOP_PADDING_DP` from `54f` to `60f`.
2. In `computeLayout`, remove the `scaleFactor` multiplication from the `topPadding` calculation so vertical spacing remains consistent regardless of the horizontal widget width (`widthScaleFactor`). The new calculation should be `val topPadding = dpToPx(context, TOP_PADDING_DP * labelScale)`.

# Verification & Testing
- Deploy the widget to an emulator or physical device.
- Verify that the Daily forecast graph's highest temperature label no longer clips into or overlaps with the header text.
- Resize the widget horizontally to confirm that the vertical gap between the header and graph remains visually consistent and does not scale improperly.
