package dev.swipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.swipe.ui.theme.SwipeTheme

/**
 * The §5.7 `Toast` pill — pure presentation (WP-9.3): a `surfaceRaised`,
 * radius-12 row for short confirmations ("Undone"). It owns no visibility
 * and no timer: it renders while its owner renders it, and screens compose
 * it through [ToastHost], which owns the 3 s auto-dismiss and the one
 * placement. An optional trailing action sits in `accent`, and [iconTint]
 * carries a §5.3 tone (the FR-22 favorite confirm is now
 * [FavoriteConfirmCard], WP-8.9, not a toast).
 */
@Composable
fun Toast(
    text: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    iconTint: Color = SwipeTheme.colors.textPrimary,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tokens = SwipeTheme.componentSizes
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth(tokens.toastWidthFraction)
                .background(SwipeTheme.colors.surfaceRaised, RoundedCornerShape(SwipeTheme.radii.toast))
                .padding(horizontal = tokens.toastPaddingHorizontal, vertical = tokens.toastPaddingVertical)
                .semantics {
                    liveRegion = LiveRegionMode.Assertive
                    this.contentDescription = text
                },
    ) {
        if (icon != null) {
            SwipeIcon(symbol = icon, tint = iconTint, contentDescription = null)
        }
        BasicText(
            text = text,
            style = SwipeTheme.typography.callout.copy(color = SwipeTheme.colors.textPrimary),
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            BasicText(
                text = actionLabel,
                style = SwipeTheme.typography.callout.copy(color = SwipeTheme.colors.accent),
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .clickable(onClick = onAction)
                        .padding(start = SwipeTheme.spacing.s)
                        .semantics {
                            role = Role.Button
                            this.contentDescription = actionLabel
                        },
            )
        }
    }
}
