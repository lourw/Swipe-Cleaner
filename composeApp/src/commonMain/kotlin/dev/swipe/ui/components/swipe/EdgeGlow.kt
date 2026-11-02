package dev.swipe.ui.components.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import dev.swipe.ui.theme.SwipeTheme

/**
 * The verdict-colored gradient behind a dragged [SwipeCard] (§5.6 "Edge
 * glow — a gradient in the verdict color on the swipe side, max 30 % alpha
 * at the threshold"). [alpha] is the drag progress (0 → 1 from
 * [stampAlpha]); it is scaled by [EDGE_GLOW_MAX_ALPHA] so the glow can never
 * exceed 30 % opacity even past the threshold.
 */
@Composable
fun EdgeGlow(
    verdict: SwipeVerdict,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val color =
        when (verdict) {
            SwipeVerdict.KEEP -> SwipeTheme.colors.keep
            SwipeVerdict.DELETE -> SwipeTheme.colors.delete
            SwipeVerdict.SKIP -> SwipeTheme.colors.skip
        }
    // The gradient runs from the swipe-side edge inward: the edge the card
    // travels toward is where the color pools.
    val brush =
        when (verdict) {
            SwipeVerdict.KEEP -> Brush.horizontalGradient(0f to color, 1f to color.copy(alpha = 0f))
            SwipeVerdict.DELETE -> Brush.horizontalGradient(0f to color.copy(alpha = 0f), 1f to color)
            SwipeVerdict.SKIP -> Brush.verticalGradient(0f to color, 1f to color.copy(alpha = 0f))
        }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .alpha(alpha * EDGE_GLOW_MAX_ALPHA)
                .background(brush),
    )
}
