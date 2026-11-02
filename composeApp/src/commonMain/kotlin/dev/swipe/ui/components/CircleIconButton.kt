package dev.swipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.swipe.ui.theme.SwipeTheme
import org.jetbrains.compose.resources.DrawableResource

/**
 * The §5.7 circular icon button: a full-round ≥44 dp touch target (§5.5) with
 * an icon inside. The [container] fill stays transparent for nav-bar use and
 * is set to a tinted verdict color by the action bar (§5.7).
 */
@Composable
fun CircleIconButton(
    resource: DrawableResource,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Color.Transparent,
    contentColor: Color = SwipeTheme.colors.textPrimary,
    iconSize: androidx.compose.ui.unit.Dp = SwipeTheme.sizes.icon,
) {
    val tokens = SwipeTheme.componentSizes
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(SwipeTheme.sizes.minTouchTarget)
                .background(container, CircleShape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    this.contentDescription = label
                },
    ) {
        SwipeIcon(
            resource = resource,
            size = iconSize,
            tint = contentColor,
            contentDescription = null,
        )
    }
}
