package dev.swipe.ui.screens.swipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.swipe.resources.Res
import dev.swipe.resources.ic_arrow_upward
import dev.swipe.resources.ic_check
import dev.swipe.resources.ic_close
import dev.swipe.resources.ic_undo
import dev.swipe.ui.components.CircleIconButton
import dev.swipe.ui.theme.SwipeTheme
import dev.swipe.ui.theme.VERDICT_TINT_ALPHA

/**
 * The §5.7 `ActionBar`: Delete (64 dp, `delete @ 16%`), Skip (48 dp,
 * `surfaceRaised`), and Keep (64 dp, `keep @ 16%`) centered with the 24 dp
 * gap, and Undo (40 dp) at the left edge (FR-15). Every button fires the
 * §5.6 *button path* — the caller runs `SwipeCardState.flyOff` before
 * sending the intent, so these callbacks are the post-flight commit.
 *
 * Undo is disabled while the deck's undo stack is empty (§6.6 `canUndo`);
 * the disabled [CircleIconButton] keeps its touch target and label (NFR-8).
 */
@Composable
fun ActionBar(
    canUndo: Boolean,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
    onSkip: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SwipeTheme.colors
    val sizes = SwipeTheme.componentSizes
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                // Safe area (WP-6.4): the buttons clear the Home Indicator.
                .navigationBarsPadding()
                .padding(vertical = SwipeTheme.spacing.m),
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(sizes.actionBarGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(
                resource = Res.drawable.ic_close,
                label = "Delete",
                onClick = onDelete,
                size = sizes.actionButtonLarge,
                container = colors.delete.copy(alpha = VERDICT_TINT_ALPHA),
                contentColor = colors.delete,
                iconSize = SwipeTheme.sizes.actionBarIcon,
            )
            CircleIconButton(
                resource = Res.drawable.ic_arrow_upward,
                label = "Skip",
                onClick = onSkip,
                size = sizes.actionButtonSkip,
                container = colors.surfaceRaised,
                contentColor = colors.skip,
                iconSize = SwipeTheme.sizes.actionBarIcon,
            )
            CircleIconButton(
                resource = Res.drawable.ic_check,
                label = "Keep",
                onClick = onKeep,
                size = sizes.actionButtonLarge,
                container = colors.keep.copy(alpha = VERDICT_TINT_ALPHA),
                contentColor = colors.keep,
                iconSize = SwipeTheme.sizes.actionBarIcon,
            )
        }
        CircleIconButton(
            resource = Res.drawable.ic_undo,
            label = "Undo",
            onClick = onUndo,
            size = sizes.actionButtonUndo,
            enabled = canUndo,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}
