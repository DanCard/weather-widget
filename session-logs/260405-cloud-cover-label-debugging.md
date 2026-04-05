# Cloud cover graph label debugging on emulator

## Problem Description
The cloud-cover hourly graph had several label-placement problems on the emulator:

1. Too many percentage labels were shown at once.
2. End labels and edge labels sometimes survived when nearby hills were more informative.
3. Left-edge labels could block nearby dip labels.
4. Preferred-below low labels visually sat on the line because the code was positioning the baseline rather than the glyph box.
5. Very low labels like `10%` still fell back above the line, and the user had to ask multiple times before runtime evidence was used to identify the real reason.

## Prompts History
1. "Emulator : lots of labels of cloud graph.  I'm thinking to increase smoothing when there are many labels.  Does that sound dumb?"
2. "When there is more than 5 labels skip extrema whose value differs only a little from nearest labeled point."
3. "Should we apply this to all the hourly graphs?"
4. "1"
5. "Implement the plan."
6. "Is there someway we can priortize peaks over valleys?  Keep peaks over dips?"
7. "In the context of reducing labels by removing nearby extrema, can we prioritize keeping hills versus dips?"
8. "Lets not protect edges when there are nearby labels.  Can you use emulator as an example.  There was a 43% for peak then close by the end label is 34%.  Can we get rid of end label 34% instead?"
9. "Might be easier to look at logs"
10. "yes implement plan"
11. "Write a comment in the code that I'm not sure if 5 or 6 labels is better, where we try to limit to 5."
12. "code review app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt"
13. "commit all and push"
14. "Emulator: cloud cover graph.  25% label on left edge feels awkward:
1) Either it should be drawn above the line since the line is trending down
2) Or the dip should be labeled instead."
15. "do it"
16. "commit all and push"
17. "Lets try removing smoothing by 1.  From two to one iteration."
18. "reduce the cloud view handler from 1 to 0"
19. "Emulator: I got 7 labels without smoothing.  Lets change the thresholds where we elimnate local extrema from 5, 10, 15, to 8, 12, 16, to see if that helps."
20. "Yes create a plan for that"
21. "Implement the plan."
22. "Increase fallback above to 14 dp"
23. "Emulator: 10% label should be below, 17% label should be below , as well as 29% label."
24. "They are not below."
25. "yes"
26. "Emulator: 10% label is way above.  Can we get that below even if there is small overlap?"
27. "do it"
28. "The 10% is way above , can you remind me what the gap is and lower it?"
29. "Lower fallback to above to 8 dp"
30. "Emulator: 10% label is way above.  Can we get that below even if there is overlap?"
31. "How about less guessing and add logging and review logging to determine why it is not drawn below?"
32. "yes"
33. "Emulator: 10% label above.  Can we get that below even if there is overlap?"
34. "lets see the plan for that"
35. "Implement the plan."
36. "Installed, inspect logs"
37. "do it"
38. "The 10% is way above , can you remind me what the gap is and lower it?"
39. "Lower fallback to above to 8 dp"
40. "Emulator: 10% label above.  Can we get that below even if there is overlap?"
41. "How about less guessing and add logging and review logging to determine why it is not drawn below?"
42. "yes"
43. "commit all and push"
44. "Write detailed session log to session-logs/ dir"
45. "Can you add something to memory to make this easier?  Why do I have to ask for something 3 times, when I shouldn't have to ask for it anytimes?"
46. "yes"
47. "commit all and push"
48. "Write detailed session log to session-logs/ dir"

## Investigation & Evidence
1. Read `CloudCoverGraphRenderer.kt`, `GraphRenderUtils.kt`, and `CloudCoverViewHandler.kt` to identify how cloud labels were selected and placed.
2. Confirmed the cloud graph originally built candidates from:
   1. global max
   2. global min
   3. both edges
   4. every local extremum on the smoothed signal
3. Verified on emulator screenshots that:
   1. a `43%` hill near the right side was less useful than the trailing `34%` edge label that survived
   2. a `25%` left-edge label could look awkward and block a nearby dip label
   3. a `10%` label still appeared above even after “below” logic was adjusted
4. Pulled renderer-specific logcat from `CloudCoverGraph` repeatedly instead of relying on code inspection alone.
5. The decisive runtime findings were:
   1. `43%` was being filtered while `34%` survived because the dense-label filter treated nearby retained labels symmetrically and still let the edge win in some cases.
   2. The `25%` left-edge label was preventing the lower valley label from placing.
   3. The `10%` label was first failing on bottom bounds, then later failing specifically because of `overlap_icon`, not because of line overlap.
6. Added targeted placement/rejection logging so the renderer now reports:
   1. whether a label failed top or bottom bounds
   2. whether extra bottom overflow was allowed
   3. whether the rejection was due to label overlap or icon overlap

## Root Causes
1. Candidate filtering was too generic:
   1. it did not prioritize hills over dips or edges strongly enough
   2. it still allowed edge labels to survive in dense cases
2. Edge behavior was too rigid:
   1. the right edge could outlive a nearby stronger peak
   2. the left edge could occupy space that a nearby low valley needed
3. Label geometry was wrong for “below” placements:
   1. the code originally offset the text baseline, not the actual glyph bounds
   2. visually, labels could still sit on the curve even when logs said they were “below”
4. The final blocker for the `10%` label was not the line or bottom bound:
   1. it was `overlap_icon` with the hourly icon strip
5. Process problem:
   1. several fixes were attempted based on likely causes before enough runtime evidence was gathered
   2. this made the user repeat the same request more times than necessary

## Changes Implemented
1. Added dense cloud-label filtering in `CloudCoverGraphRenderer.kt`.
2. Limited dense cases with a candidate cap of `5` and documented uncertainty about whether `5` or `6` is better.
3. Changed dense-label thresholds from `5, 10, 15` to `8, 12, 16`.
4. Prioritized candidate retention as:
   1. global max
   2. peak
   3. global min
   4. valley
   5. edge
5. Stopped protecting edges in dense cases, so nearby hills can beat end labels like `34%`.
6. Added left-edge suppression logic so a non-peak left-edge label yields to a nearby lower valley.
7. Reduced cloud smoothing in the live widget path by changing `CloudCoverViewHandler.smoothingIterationsFor` so both wide and narrow cloud cover now use `0` iterations.
8. Split cloud label gaps into preferred vs fallback values:
   1. preferred above `2dp`
   2. preferred below `2dp`
   3. fallback above `8dp`
   4. fallback below `14dp`
9. Changed vertical label geometry to use font metrics so “below” means the glyph box clears the curve rather than just moving the baseline.
10. Allowed low preferred-below labels to use a small amount of extra bottom overflow.
11. Allowed low preferred-below labels to ignore hourly icon overlap in narrow cases so low values like `10%` can stay below.
12. Added more explicit `CloudCoverGraph` rejection logging for:
   1. bounds failures
   2. overflow allowance
   3. icon overlap vs label overlap
13. Added a repo instruction to `AGENTS.md`:
   1. if the user says the widget still looks wrong after a change, emulator screenshot plus renderer-specific logcat must be treated as the source of truth over code inspection

## Verification
1. Repeatedly inspected the emulator with:
   1. screenshots via `adb exec-out screencap`
   2. focused `CloudCoverGraph` logcat
2. Ran focused unit tests after the renderer changes:
   1. `./gradlew testDebugUnitTest --tests com.weatherwidget.widget.CloudCoverGraphRendererTest`
   2. `./gradlew testDebugUnitTest --tests com.weatherwidget.widget.handlers.CloudCoverViewHandlerTest`
3. Added or updated tests covering:
   1. dense filter prefers peaks over nearby valleys
   2. dense filter prefers peaks over nearby edge labels
   3. global extrema protection
   4. left-edge suppression for nearby valleys
   5. preferred vs fallback gap values
   6. below-placement glyph geometry
   7. low-label bottom-overflow allowance
   8. low-label icon-overlap allowance

## Commits
1. `7568af8` `Refine cloud graph label filtering`
2. `c8f2ddc` `Improve left-edge cloud label selection`
3. `972bcfd` `Tighten cloud label placement debugging`

## Process Correction
1. The user explicitly called out that the `10%` label should have been handled much sooner.
2. That feedback was valid.
3. The concrete correction made in this session was:
   1. add a repo-level debugging rule in `AGENTS.md`
   2. use screenshot plus renderer logcat as the primary evidence for future widget rendering disagreements
   3. add targeted renderer diagnostics before iterating on fixes

## Files Modified During This Session
1. `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt`
2. `app/src/test/java/com/weatherwidget/widget/CloudCoverGraphRendererTest.kt`
3. `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`
4. `app/src/test/java/com/weatherwidget/widget/handlers/CloudCoverViewHandlerTest.kt`
5. `AGENTS.md`
