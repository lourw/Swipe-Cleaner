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
import androidx.compose.ui.unit.Dp
import dev.swipe.ui.theme.SwipeTheme
import org.jetbrains.compose.resources.DrawableResource

/**
 * The §5.7 circular icon button: a full-round icon inside [size] (Skip 48 dp,
 * Keep/Delete 64 dp in the action bar; 40 dp for Undo). The clickable area is
 * always at least [SwipeSizes.minTouchTarget] wide and tall (§5.5), so the
 * 40 dp Undo variant keeps a compliant touch target. The [container] fill
 * stays transparent for nav-bar use and is set to a tinted verdict color by
 * the action bar (§5.7).
 */
@Composable
fun CircleIconButton(
    resource: DrawableResource,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = SwipeTheme.sizes.minTouchTarget,
    container: Color = Color.Transparent,
    contentColor: Color = SwipeTheme.colors.textPrimary,
    iconSize: Dp = SwipeTheme.sizes.icon,
) {
    val minTouch = SwipeTheme.sizes.minTouchTarget
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(if (size > minTouch) size else minTouch)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    this.contentDescription = label
                },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(size)
                    .background(container, CircleShape),
        ) {
            SwipeIcon(
                resource = resource,
                size = iconSize,
                tint = contentColor,
                contentDescription = null,
            )
        }
    }
}
