package dev.swipe.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale from REQUIREMENTS §5.5. */
@Immutable
data class SwipeSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val s: Dp = 12.dp,
    val m: Dp = 16.dp,
    val l: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
    val gutter: Dp = 16.dp,
)

/** Corner radii from REQUIREMENTS §5.5 and §5.7. Buttons are fully round, so they have no token. */
@Immutable
data class SwipeRadii(
    val card: Dp = 24.dp,
    val sheet: Dp = 20.dp,
    val toast: Dp = 12.dp,
    val tile: Dp = 8.dp,
    val stamp: Dp = 8.dp,
)

/** Shared sizes from REQUIREMENTS §5.5. Component-specific sizes live with their components' specs. */
@Immutable
data class SwipeSizes(
    val minTouchTarget: Dp = 44.dp,
    val hairline: Dp = 1.dp,
    val icon: Dp = 24.dp,
    val actionBarIcon: Dp = 28.dp,
)
