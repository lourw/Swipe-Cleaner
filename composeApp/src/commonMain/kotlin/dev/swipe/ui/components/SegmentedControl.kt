package dev.swipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
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
 * full-round track and segments — buttons are fully round (§5.5) — on a
 * `surface` track; the selected segment fills with `accent` and draws its
 * label in the card-foreground color. Segments are selectable tabs with
 * `selected` semantics; each is at least 44 dp tall.
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
                .background(SwipeTheme.colors.surface, CircleShape)
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
    // The clickable node spans the 44 dp touch target (§5.5); the pill fill
    // stays at the slimmer visual height inside it.
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .heightIn(min = SwipeTheme.sizes.minTouchTarget)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Tab
                    this.selected = selected
                    this.contentDescription = label
                },
    ) {
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
                        CircleShape,
                    ).padding(horizontal = SwipeTheme.spacing.m),
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
}
