package dev.swipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.swipe.ui.theme.SwipeTheme

/**
 * A pill segmented control for the M1 media-type filter (REQUIREMENTS §5.7):
 * full-round segments on a `surface` track; the selected segment fills with
 * `accent` and draws its label in the card-foreground color. Segments are
 * selectable tabs with `selected` semantics; each is at least 44 dp tall.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .background(SwipeTheme.colors.surface, RoundedCornerShape(SwipeTheme.radii.tile))
                .padding(SwipeTheme.spacing.xxs),
    ) {
        options.forEach { option ->
            Segment(
                label = labelOf(option),
                selected = option == selected,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = SwipeTheme.componentSizes
    val colors = SwipeTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .heightIn(min = tokens.segmentHeight)
                .background(
                    if (selected) {
                        colors.accent
                    } else {
                        Color.Transparent
                    },
                    RoundedCornerShape(SwipeTheme.radii.tile),
                ).clickable(onClick = onClick)
                .padding(horizontal = SwipeTheme.spacing.m)
                .semantics {
                    role = Role.Tab
                    this.selected = selected
                    this.contentDescription = label
                },
    ) {
        BasicText(
            text = label,
            style =
                SwipeTheme.typography.callout.copy(
                    color =
                        if (selected) {
                            colors.textPrimary
                        } else {
                            colors.textSecondary
                        },
                ),
        )
    }
}
