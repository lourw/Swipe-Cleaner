package dev.swipe.ui.screens.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.swipe.ui.components.PrimaryButton
import dev.swipe.ui.theme.SwipeTheme

/**
 * The §5.8 empty state: "Nothing left here 🎉" (the one allowed emoji) and
 * a "Try another source" CTA back to Home. Shown when a deck is empty with
 * no session summary to deliver — an empty or filtered-out source, or a
 * session left open after the WP-2.2 exhausted-deck edge.
 */
@Composable
fun EmptyState(
    onTryAnotherSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxSize()
                .background(SwipeTheme.colors.bg)
                .padding(horizontal = SwipeTheme.spacing.gutter),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.l),
        ) {
            BasicText(
                text = "Nothing left here 🎉",
                style =
                    SwipeTheme.typography.title.copy(
                        color = SwipeTheme.colors.textPrimary,
                        textAlign = TextAlign.Center,
                    ),
            )
            PrimaryButton(
                label = "Try another source",
                onClick = onTryAnotherSource,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
