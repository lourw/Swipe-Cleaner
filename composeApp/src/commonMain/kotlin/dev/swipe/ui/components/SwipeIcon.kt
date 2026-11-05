package dev.swipe.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import dev.swipe.ui.theme.SwipeTheme
import dev.vicart.compose.material.symbols.MaterialSymbol

/**
 * A Material Symbols Rounded glyph (REQUIREMENTS §5.5), drawn at [size] dp and
 * tinted [tint] through the variable font bundled by
 * `dev.vicart:compose-material-symbols`. Symbol names come from [SwipeIcons].
 *
 * When [contentDescription] is null the icon is hidden from accessibility
 * services (decorative); otherwise it is announced. The library renders the
 * glyph with a `BasicText`, so the symbol *name* would leak into the tree as
 * text semantics; `clearAndSetSemantics` replaces the subtree's semantics with
 * exactly what we set — the description alone, or nothing at all (NFR-8).
 */
@Composable
fun SwipeIcon(
    symbol: String,
    modifier: Modifier = Modifier,
    size: Dp = SwipeTheme.sizes.icon,
    tint: Color = SwipeTheme.colors.textPrimary,
    contentDescription: String? = null,
) {
    val a11yModifier =
        if (contentDescription != null) {
            Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
        } else {
            Modifier.clearAndSetSemantics { }
        }
    MaterialSymbol.Outlined.Rounded(
        icon = symbol,
        size = size,
        tint = tint,
        modifier = modifier.size(size).then(a11yModifier),
    )
}
