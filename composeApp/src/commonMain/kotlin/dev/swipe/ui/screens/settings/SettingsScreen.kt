package dev.swipe.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.swipe.ui.screens.home.ScreenHeader
import dev.swipe.ui.theme.SwipeTheme

/**
 * Placeholder Settings for WP-5.3: the Home gear needs a destination so the
 * §6.2 navigation is complete. WP-6.1 replaces this with the real FR-29–31
 * screen (toggles bound to `SettingsStore`, reset, provisioning expiry).
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(SwipeTheme.colors.bg),
    ) {
        ScreenHeader(title = "Settings", onBack = onBack)
        BasicText(
            text = "Settings are coming soon.",
            style = SwipeTheme.typography.callout.copy(color = SwipeTheme.colors.textSecondary),
            modifier = Modifier.padding(horizontal = SwipeTheme.spacing.gutter),
        )
    }
}
