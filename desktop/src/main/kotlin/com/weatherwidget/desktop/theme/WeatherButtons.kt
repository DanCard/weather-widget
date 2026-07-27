package com.weatherwidget.desktop.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.weatherwidget.shared.util.WeatherThemeTokens

/**
 * Reusable color-coded action buttons mirroring the Android drawable palette
 * (`rounded_button_{green,blue,navy,yellow}.xml`). Phase 2 ships the components; Phase 4 wires
 * them into `SettingsWindow` so the desktop gets the same color-by-action convention Android uses.
 *
 * Color mapping (matches `activity_settings.xml`):
 * - [primaryAction] = green   — Refresh Data, Set Location (`rounded_button_green`)
 * - [secondaryAction] = blue  — View App Logs, View Icon Gallery (`rounded_button_blue`)
 * - [tertiaryAction] = navy   — Get API Key, Change App Language (`rounded_button_navy`)
 * - [alertAction] = yellow    — Submit Bug Report (`rounded_button_yellow`)
 *
 * All four use the same 12dp corner radius as the Android drawables.
 */

private val buttonShape = RoundedCornerShape(WeatherThemeTokens.BUTTON_CORNER_DP.dp)

private val defaultButtonPadding = PaddingValues(
    horizontal = 16.dp,
    vertical = 10.dp,
)

@Composable
private fun buttonColors(
    container: Color,
    content: Color,
): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = container,
    contentColor = content,
)

/**
 * The generic color-coded button. The four named wrappers below are the recommended entry
 * points — they pin the palette to the Android drawable colors so individual call sites can't
 * drift.
 */
@Composable
fun ActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color,
    contentColor: Color,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = buttonShape,
        colors = buttonColors(containerColor, contentColor),
        contentPadding = defaultButtonPadding,
        content = content,
    )
}

/** Green "go" actions: Refresh Data, Set Location. Dark text on light-green fill. */
@Composable
fun PrimaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    ActionButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = Color(WeatherThemeTokens.BUTTON_GREEN),
        contentColor = Color(WeatherThemeTokens.ON_PRIMARY_DARK),
        content = content,
    )
}

/** Blue secondary actions: View App Logs, View Icon Gallery. */
@Composable
fun SecondaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    ActionButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = Color(WeatherThemeTokens.BUTTON_BLUE),
        contentColor = Color(WeatherThemeTokens.ON_PRIMARY_DARK),
        content = content,
    )
}

/** Navy tertiary actions: Get API Key, Change App Language. White text on dark navy fill. */
@Composable
fun TertiaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    ActionButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = Color(WeatherThemeTokens.BUTTON_NAVY),
        contentColor = Color(WeatherThemeTokens.ON_SURFACE),
        content = content,
    )
}

/** Yellow alert actions: Submit Bug Report. Dark text on bright yellow fill. */
@Composable
fun AlertActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    ActionButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = Color(WeatherThemeTokens.BUTTON_YELLOW),
        contentColor = Color(WeatherThemeTokens.ON_PRIMARY_DARK),
        content = content,
    )
}

/**
 * Outline-style "destructive"/secondary outline button (e.g. Exit app). Uses MaterialTheme's
 * `outline` color so it tracks the [WeatherDarkColorScheme] (which sets it to the Android card
 * stroke color).
 */
@Composable
fun WeatherOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    border: BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = buttonShape,
        border = border,
        contentPadding = defaultButtonPadding,
        content = content,
    )
}
