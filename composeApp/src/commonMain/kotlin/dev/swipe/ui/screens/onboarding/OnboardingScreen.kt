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
 * FR-1 onboarding: shown on first launch, before `PHAccessLevel.readWrite` is
 * requested. Explains the three verdicts and the batch-delete safety promise,
 * then offers a single `PrimaryButton` that triggers the system prompt.
 */
@Composable
fun OnboardingScreen(
    onAllowAccess: () -> Unit,
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
            text = "Swipe Cleaner",
            style = SwipeTheme.typography.display.copy(color = colors.textPrimary),
        )
        Spacer(modifier = Modifier.height(spacing.m))
        BasicText(
            text =
                "Go through your library one item at a time. Swipe right to keep, " +
                    "left to delete, up to skip.",
            style = SwipeTheme.typography.body.copy(color = colors.textSecondary),
        )
        Spacer(modifier = Modifier.height(spacing.m))
        BasicText(
            text =
                "Nothing is deleted as you swipe. Items you mark are collected " +
                    "into a queue you review and delete in one batch.",
            style = SwipeTheme.typography.body.copy(color = colors.textSecondary),
        )
        Spacer(modifier = Modifier.weight(1f))
        PrimaryButton(
            label = "Allow access",
            onClick = onAllowAccess,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
