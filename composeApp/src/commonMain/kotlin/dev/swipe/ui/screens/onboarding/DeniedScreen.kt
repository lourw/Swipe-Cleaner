package dev.swipe.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.swipe.ui.components.PrimaryButton
import dev.swipe.ui.theme.SwipeTheme

/**
 * FR-3 blocking screen for denied photo access: explains why access is needed
 * and offers a `PrimaryButton` "Open Settings" that jumps to the system
 * permission pane via `SystemActions.openSettings()`.
 */
@Composable
fun DeniedScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SwipeTheme.colors
    val spacing = SwipeTheme.spacing
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.bg)
                .padding(horizontal = spacing.gutter, vertical = spacing.l),
    ) {
        Spacer(modifier = Modifier.height(spacing.xl))
        BasicText(
            text = "Photos access needed",
            style = SwipeTheme.typography.title.copy(color = colors.textPrimary),
        )
        Spacer(modifier = Modifier.height(spacing.m))
        BasicText(
            text =
                "Swipe Cleaner needs access to your photo library to sort through " +
                    "it. Access is off right now.",
            style = SwipeTheme.typography.body.copy(color = colors.textSecondary),
        )
        Spacer(modifier = Modifier.height(spacing.s))
        BasicText(
            text = "Open Settings, go to Photos, and allow full access.",
            style = SwipeTheme.typography.body.copy(color = colors.textSecondary),
        )
        Spacer(modifier = Modifier.weight(1f))
        PrimaryButton(
            label = "Open Settings",
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
