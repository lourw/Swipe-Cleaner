package dev.swipe.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
        modifier = modifier.size(size).semantics {
            this.contentDescription = contentDescription ?: ""
        },
    )
}

/**
 * The `system_default` icon treatment (REQUIREMENTS §5.4): the platform
 * render of a 17 sp SF Symbol glyph in the secondary text color. No bundled
 * font — drawn as text so it follows the system font. Glyph [glyph] is a raw
 * unicode string, e.g. "✓" for a check placeholder in the debug gallery.
 */
@Composable
fun SystemDefaultIcon(
    glyph: String,
    modifier: Modifier = Modifier,
    tint: Color = SwipeTheme.colors.textSecondary,
    contentDescription: String? = null,
) {
    BasicText(
        text = glyph,
        style =
            TextStyle(
                fontFamily = SwipeTheme.typography.body.fontFamily,
                fontSize = SwipeTheme.componentSizes.systemDefaultIconSize,
                color = tint,
            ),
        modifier = modifier.semantics {
            role = Role.Image
            this.contentDescription = contentDescription ?: ""
        },
    )
}
