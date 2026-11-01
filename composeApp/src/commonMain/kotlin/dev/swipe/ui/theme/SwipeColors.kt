package dev.swipe.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Color tokens from REQUIREMENTS §5.3. This file is the only place hex values may appear.
 * `keep`, `delete` and `skip` are verdict colors and are used for nothing else.
 */
@Immutable
data class SwipeColors(
    val bg: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val scrim: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val keep: Color,
    val delete: Color,
    val skip: Color,
    val warning: Color,
)

/** Alpha for verdict-tinted fills, for example the Keep button background (§5.3). */
const val VERDICT_TINT_ALPHA = 0.16f

private const val SCRIM_ALPHA = 0.55f

internal val DarkSwipeColors =
    SwipeColors(
        bg = Color(0xFF000000),
        surface = Color(0xFF121214),
        surfaceRaised = Color(0xFF1C1C1F),
        border = Color(0xFF2C2C30),
        scrim = Color(0xFF000000).copy(alpha = SCRIM_ALPHA),
        textPrimary = Color(0xFFF5F5F7),
        textSecondary = Color(0xFFA1A1A6),
        textTertiary = Color(0xFF6E6E73),
        accent = Color(0xFF0A84FF),
        keep = Color(0xFF30D158),
        delete = Color(0xFFFF453A),
        skip = Color(0xFF8E8E93),
        warning = Color(0xFFFFD60A),
    )
