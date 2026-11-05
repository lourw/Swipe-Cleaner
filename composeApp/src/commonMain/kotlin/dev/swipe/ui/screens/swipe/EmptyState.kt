package dev.swipe.ui.screens.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.swipe.ui.components.PrimaryButton
import dev.swipe.ui.theme.SwipeTheme

/**
 * The §5.8 empty state: "Nothing left here 🎉" (the one allowed emoji) and
 * a CTA back to Home — "Try another source" on a deck, retitled "Back home"
 * on the Review queue, where there is no source to pick. Shown when a deck
 * is empty with no session summary to deliver — an empty or filtered-out
 * source, or a session left open after the WP-2.2 exhausted-deck edge — and
 * for an empty queue (FR-23, §6.7's zero-count feedback).
 */
@Composable
fun EmptyState(
    onTryAnotherSource: () -> Unit,
    modifier: Modifier = Modifier,
    ctaLabel: String = "Try another source",
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxSize()
                .background(SwipeTheme.colors.bg)
                // Safe areas (WP-6.4): the centered copy stays inside the
                // safe area; bg paints edge-to-edge.
                .statusBarsPadding()
                .navigationBarsPadding()
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
                label = ctaLabel,
                onClick = onTryAnotherSource,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
