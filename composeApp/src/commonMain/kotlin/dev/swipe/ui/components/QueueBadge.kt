package dev.swipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.swipe.ui.theme.SwipeComponentSizes
import dev.swipe.ui.theme.SwipeTheme
import dev.swipe.ui.theme.TABULAR_FIGURES

/**
 * The §5.7 `QueueBadge`: a `delete` pill, at least 20 dp, `caption`-sized
 * count text in the card-foreground color. Shows `99+` when the count is
 * higher than [SwipeComponentSizes.badgeMaxShown].
 */
@Composable
fun QueueBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    val tokens = SwipeTheme.componentSizes
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .heightIn(min = tokens.badgeMinSize)
                .background(SwipeTheme.colors.delete, CircleShape)
                .padding(horizontal = SwipeTheme.spacing.xxs)
                .semantics { contentDescription = badgeDescription(count) },
    ) {
        BasicText(
            text = badgeLabel(count, tokens.badgeMaxShown),
            style =
                SwipeTheme.typography.caption.copy(
                    color = SwipeTheme.colors.textPrimary,
                    fontFeatureSettings = TABULAR_FIGURES,
                ),
        )
    }
}

/** §5.7: display 99+ above the badge max (99). */
fun badgeLabel(
    count: Int,
    maxShown: Int = SwipeComponentSizes().badgeMaxShown,
): String = if (count > maxShown) "$maxShown+" else count.toString()

/** Accessibility description a caller can override with context-aware copy. */
fun badgeDescription(
    count: Int,
    maxShown: Int = SwipeComponentSizes().badgeMaxShown,
): String = "${badgeLabel(count, maxShown)} queued"
