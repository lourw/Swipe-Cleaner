package dev.swipe.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type tokens from REQUIREMENTS §5.4. `stamp` text is uppercased by the component that draws it.
 * Numbers that change (counters, progress) add [TABULAR_FIGURES] as `fontFeatureSettings`.
 */
@Immutable
data class SwipeTypography(
    val display: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val callout: TextStyle,
    val caption: TextStyle,
    val stamp: TextStyle,
)

const val TABULAR_FIGURES = "tnum"

internal val DefaultSwipeTypography =
    SwipeTypography(
        display = style(size = 34, line = 41, weight = FontWeight.Bold),
        title = style(size = 22, line = 28, weight = FontWeight.SemiBold),
        headline = style(size = 17, line = 22, weight = FontWeight.SemiBold),
        body = style(size = 17, line = 22, weight = FontWeight.Normal),
        callout = style(size = 15, line = 20, weight = FontWeight.Normal),
        caption = style(size = 12, line = 16, weight = FontWeight.Medium),
        stamp = style(size = 40, line = 44, weight = FontWeight.Black).copy(letterSpacing = 2.sp),
    )

private fun style(
    size: Int,
    line: Int,
    weight: FontWeight,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
)
