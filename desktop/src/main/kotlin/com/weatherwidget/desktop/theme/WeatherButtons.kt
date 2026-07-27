package com.weatherwidget.desktop.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.shared.util.WeatherThemeTokens

/**
 * Reusable color-coded action buttons mirroring the Android drawable palette
 * (`rounded_button_{green,blue,navy,yellow}.xml`). Phase 2 shipped the initial set; this rev
 * (post-Phase-4 user feedback) corrects the text-color mapping and adds a [prominent] variant for
 * the big 21sp Android action buttons (Set Location, Submit Bug Report) that the original release
 * was missing.
 *
 * Android `activity_settings.xml` text-color convention (verified per-button, not assumed):
 *
 *  - Green HEADER buttons (Refresh Data)         -> white text, 14sp, 10dp vertical pad
 *  - Green PROMINENT buttons (Set Location)      -> dark text, 21sp, 16dp vertical pad
 *  - Blue (all sizes, View App Logs / Gallery)   -> white text
 *  - Navy (all sizes, Get API Key / Language)    -> white text
 *  - Yellow (all sizes, Submit Bug Report)       -> dark text
 *
 * All four named wrappers pin their colors so individual call sites can't drift; the [prominent]
 * flag switches the bigger 21sp + 18dp padding for the "big action" Android buttons. 12dp corner
 * radius matches `rounded_button_*.xml` everywhere.
 *
 * API: each named wrapper takes `text: String` (not a content lambda) so the typography stays
 * pinned inside this file. Call sites that need a non-text child (icon rows etc.) can still use
 * the generic [ActionButton] overload below.
 */

private val buttonShape = RoundedCornerShape(WeatherThemeTokens.BUTTON_CORNER_DP.dp)

private val standardButtonPadding = PaddingValues(
    horizontal = 20.dp,
    vertical = 14.dp,
)
private val prominentButtonPadding = PaddingValues(
    horizontal = 24.dp,
    vertical = 18.dp,
)

private val STANDARD_TEXT_SP = 16.sp
private val PROMINENT_TEXT_SP = WeatherThemeTokens.ACTION_BUTTON_SP.sp // 21sp

@Composable
private fun buttonColors(
    container: Color,
    content: Color,
): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = container,
    contentColor = content,
)

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    prominent: Boolean,
    containerColor: Color,
    contentColor: Color,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = buttonShape,
        colors = buttonColors(containerColor, contentColor),
        contentPadding = if (prominent) prominentButtonPadding else standardButtonPadding,
    ) {
        Text(
            text = text,
            fontSize = if (prominent) PROMINENT_TEXT_SP else STANDARD_TEXT_SP,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Green HEADER action: Refresh Data, Save. **White** text on green fill — matches Android's
 * `refresh_data_button` (activity_settings.xml:55-67), the dominant small-green-button convention.
 *
 * For the big green "Set Location"-style action call sites should use [PrimaryActionProminentButton]
 * instead — Android switches to dark text + 21sp for that one.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ActionButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        prominent = false,
        containerColor = Color(WeatherThemeTokens.BUTTON_GREEN),
        contentColor = Color(WeatherThemeTokens.ON_SURFACE), // white
    )
}

/**
 * Green PROMINENT action: Change Location / Set Location. **Dark** text on green fill at 21sp,
 * matching Android's `set_location_button` (activity_settings.xml:278-287). The dark-on-green
 * contrast is the visual cue that this is the screen's primary "go" affordance.
 */
@Composable
fun PrimaryActionProminentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ActionButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        prominent = true,
        containerColor = Color(WeatherThemeTokens.BUTTON_GREEN),
        contentColor = Color(WeatherThemeTokens.ON_PRIMARY_DARK), // dark
    )
}

/**
 * Blue action: View App Logs, View Icon Gallery, Stations/Observations. **White** text on blue
 * fill at all sizes — matches every blue button in `activity_settings.xml`
 * (`view_app_logs_button`, `view_icon_gallery_button`). Pass [prominent] = true for the big 21sp
 * "View Icon Gallery" form.
 */
@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = false,
) {
    ActionButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        prominent = prominent,
        containerColor = Color(WeatherThemeTokens.BUTTON_BLUE),
        contentColor = Color(WeatherThemeTokens.ON_SURFACE), // white
    )
}

/**
 * Navy action: Get API Key, Change App Language. **White** text on dark navy fill, matching
 * Android's `app_language_button`. The container is already dark so white text is the only
 * readable choice; no prominent variant exists in Android and we don't add one here.
 */
@Composable
fun TertiaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ActionButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        prominent = false,
        containerColor = Color(WeatherThemeTokens.BUTTON_NAVY),
        contentColor = Color(WeatherThemeTokens.ON_SURFACE), // white
    )
}

/**
 * Yellow alert: Submit Bug Report. **Dark** text on bright yellow fill at 21sp, matching
 * Android's `submit_bug_report_button`. Yellow is too bright for white text at any size — this
 * is the only button where dark text is universal.
 */
@Composable
fun AlertActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ActionButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        prominent = true, // Android's submit_bug_report_button is a big 21sp action.
        containerColor = Color(WeatherThemeTokens.BUTTON_YELLOW),
        contentColor = Color(WeatherThemeTokens.ON_PRIMARY_DARK), // dark
    )
}

/**
 * Outline-style "destructive"/secondary outline button (e.g. Exit app). Uses MaterialTheme's
 * `outline` color so it tracks the [WeatherDarkColorScheme] (which sets it to the Android card
 * stroke color). White text via the default `MaterialTheme.colorScheme.onSurface`.
 */
@Composable
fun WeatherOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    border: BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = buttonShape,
        border = border,
        contentPadding = standardButtonPadding,
    ) {
        Text(
            text = text,
            fontSize = STANDARD_TEXT_SP,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
