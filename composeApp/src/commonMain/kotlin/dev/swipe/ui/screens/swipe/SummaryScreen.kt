package dev.swipe.ui.screens.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.swipe.ui.components.PrimaryButton
import dev.swipe.ui.theme.SwipeTheme
import dev.swipe.ui.theme.TABULAR_FIGURES

/**
 * The FR-21 session summary (§5.8): three centered rows of a `display`
 * count with a verdict-colored label — `Kept 92`, `Queued 18`, `Skipped 4` —
 * and the primary CTA: "Review N to delete" when anything is queued (takes
 * the user to the queue), "Back home" otherwise.
 *
 * Reached with the counts on the route, so it recomposes on nothing; the
 * Swipe session ended when [dev.swipe.presentation.swipe.SwipeUiState.finished]
 * was set, and Undo is a no-op from here (the stack ended with the session).
 */
@Composable
fun SummaryScreen(
    kept: Int,
    queued: Int,
    skipped: Int,
    onBackHome: () -> Unit,
    onReviewQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SwipeTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.bg)
                // Safe areas (WP-6.4): the centered content stays inside the
                // safe area; bg paints edge-to-edge.
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = SwipeTheme.spacing.gutter),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.l),
        ) {
            SummaryRow(count = kept, label = "Kept", color = colors.keep)
            SummaryRow(count = queued, label = "Queued", color = colors.delete)
            SummaryRow(count = skipped, label = "Skipped", color = colors.skip)
            if (queued > 0) {
                PrimaryButton(
                    label = "Review $queued to delete",
                    onClick = onReviewQueue,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                PrimaryButton(
                    label = "Back home",
                    onClick = onBackHome,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** One `display` count + verdict-colored label row (§5.8). */
@Composable
private fun SummaryRow(
    count: Int,
    label: String,
    color: Color,
) {
    val colors = SwipeTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.xs),
    ) {
        BasicText(
            text = count.toString(),
            style =
                SwipeTheme.typography.display.copy(
                    color = colors.textPrimary,
                    fontFeatureSettings = TABULAR_FIGURES,
                ),
        )
        BasicText(
            text = label,
            style = SwipeTheme.typography.headline.copy(color = color),
        )
    }
}
