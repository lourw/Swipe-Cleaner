package dev.swipe.ui.components.swipe

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.swipe.ui.theme.SwipeTheme

/**
 * The outlined KEEP / DELETE / SKIP label overlaid on a dragged
 * [SwipeCard] (§5.7): uppercase `stamp` typography, a 3 dp rounded stroke,
 * verdict color, tilted ±[STAMP_ROTATION_DEG] for the horizontal verdicts.
 * [alpha] comes from [stampAlpha] — the component itself never animates, it
 * just renders whatever progress the drag reports.
 */
@Composable
fun VerdictStamp(
    verdict: SwipeVerdict,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val label =
        when (verdict) {
            SwipeVerdict.KEEP -> "KEEP"
            SwipeVerdict.DELETE -> "DELETE"
            SwipeVerdict.SKIP -> "SKIP"
        }
    val color =
        when (verdict) {
            SwipeVerdict.KEEP -> SwipeTheme.colors.keep
            SwipeVerdict.DELETE -> SwipeTheme.colors.delete
            SwipeVerdict.SKIP -> SwipeTheme.colors.skip
        }
    val rotation =
        when (verdict) {
            SwipeVerdict.KEEP -> -STAMP_ROTATION_DEG
            SwipeVerdict.DELETE -> STAMP_ROTATION_DEG
            SwipeVerdict.SKIP -> 0f
        }
    Box(
        modifier =
            modifier
                .alpha(alpha)
                .rotate(rotation)
                .border(
                    width = SwipeTheme.componentSizes.stampStrokeWidth,
                    color = color,
                    shape = RoundedCornerShape(SwipeTheme.radii.stamp),
                ).padding(
                    horizontal = SwipeTheme.spacing.m,
                    vertical = SwipeTheme.spacing.xs,
                )
                // The stamp mirrors the drag; without this it would announce
                // itself three times over during a swipe.
                .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = SwipeTheme.typography.stamp.copy(color = color),
        )
    }
}
