package com.weatherwidget.desktop.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.weatherwidget.shared.util.WeatherThemeTokens

/**
 * A bordered settings card mirroring Android's `bg_surface_card.xml`: 14dp corner radius, 1dp
 * stroke in the surface-stroke color, surface-color fill, 16dp inner padding. Each section in
 * `SettingsWindow` (API Sources, Personal Stations, API Keys, Icon Gallery, Location, Units,
 * Diagnostics) is one of these.
 *
 * Visual parity target — `app/src/main/res/drawable/bg_surface_card.xml`:
 *  - shape: rectangle, corner radius = 14dp
 *  - fill: `surface_card` (#2A2A2E)
 *  - stroke: 1dp `surface_card_stroke` (#3A3A3E)
 *
 * The card also encapsulates the section-header pattern: a bold 16sp title at the top, 8dp gap
 * to the content, then [content]. The caller no longer needs to emit `Text(... titleMedium,
 * color = primary)` + `Spacer(8.dp)` per section.
 *
 * The 24dp bottom margin matches Android's section gap (`marginTop=24dp` on each section header
 * in `activity_settings.xml`); the card consumes it as bottom padding rather than as a trailing
 * spacer so callers can just chain cards in a `Column`.
 *
 * Phase 3 of the desktop settings parity plan (`plans/260727-desktop-settings-parity-with-android.md`).
 */
@Composable
fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = WeatherThemeTokens.SECTION_GAP_DP.dp),
        shape = RoundedCornerShape(WeatherThemeTokens.CARD_CORNER_DP.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(WeatherThemeTokens.CARD_PADDING_DP.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
