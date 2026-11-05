package dev.swipe.ui.screens.summary

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
import dev.swipe.ui.components.PrimaryButton
import dev.swipe.ui.theme.SwipeTheme
import dev.swipe.ui.theme.TABULAR_FIGURES

/**
 * The FR-26 delete-success screen (§5.8): a centered `display` count with
 * the `delete`-colored label — `Deleted` — and a "Back home" CTA, mirroring
 * the session summary's row. Reached from the queue's
 * [dev.swipe.presentation.queue.QueueEvent.ShowDeleteSuccess] with the
 * committed count on the route, and only for a positive count (§6.7: a
 * zero-count commit reports through the queue's own empty state instead).
 */
@Composable
fun DeleteSuccessScreen(
    deleted: Int,
    onBackHome: () -> Unit,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.xs),
            ) {
                BasicText(
                    text = deleted.toString(),
                    style =
                        SwipeTheme.typography.display.copy(
                            color = colors.textPrimary,
                            fontFeatureSettings = TABULAR_FIGURES,
                        ),
                )
                BasicText(
                    text = "Deleted",
                    style = SwipeTheme.typography.headline.copy(color = colors.delete),
                )
            }
            PrimaryButton(
                label = "Back home",
                onClick = onBackHome,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
