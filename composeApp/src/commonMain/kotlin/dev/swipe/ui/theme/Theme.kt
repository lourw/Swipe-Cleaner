package dev.swipe.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalSwipeColors = staticCompositionLocalOf { DarkSwipeColors }
private val LocalSwipeTypography = staticCompositionLocalOf { DefaultSwipeTypography }
private val LocalSwipeSpacing = staticCompositionLocalOf { SwipeSpacing() }
private val LocalSwipeRadii = staticCompositionLocalOf { SwipeRadii() }
private val LocalSwipeSizes = staticCompositionLocalOf { SwipeSizes() }

/** Provides the design tokens (REQUIREMENTS §5.2). v1 is dark only. */
@Composable
fun SwipeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSwipeColors provides DarkSwipeColors,
        LocalSwipeTypography provides DefaultSwipeTypography,
        LocalSwipeSpacing provides SwipeSpacing(),
        LocalSwipeRadii provides SwipeRadii(),
        LocalSwipeSizes provides SwipeSizes(),
        content = content,
    )
}

/** Read tokens with `SwipeTheme.colors.keep`, `SwipeTheme.spacing.gutter`, and so on. */
object SwipeTheme {
    val colors: SwipeColors
        @Composable @ReadOnlyComposable
        get() = LocalSwipeColors.current

    val typography: SwipeTypography
        @Composable @ReadOnlyComposable
        get() = LocalSwipeTypography.current

    val spacing: SwipeSpacing
        @Composable @ReadOnlyComposable
        get() = LocalSwipeSpacing.current

    val radii: SwipeRadii
        @Composable @ReadOnlyComposable
        get() = LocalSwipeRadii.current

    val sizes: SwipeSizes
        @Composable @ReadOnlyComposable
        get() = LocalSwipeSizes.current
}
