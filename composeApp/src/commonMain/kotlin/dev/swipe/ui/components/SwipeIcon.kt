package dev.swipe.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import dev.swipe.ui.theme.SwipeTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * A Material Symbols Rounded vector (REQUIREMENTS §5.5), drawn at [size] dp
 * and tinted [tint]. When [contentDescription] is null the icon is hidden
 * from accessibility services (decorative); otherwise it is announced.
 */
@Composable
fun SwipeIcon(
    resource: DrawableResource,
    modifier: Modifier = Modifier,
    size: Dp = SwipeTheme.sizes.icon,
    tint: Color = SwipeTheme.colors.textPrimary,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(resource),
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}
