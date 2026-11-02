package dev.swipe.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** Corner radii from REQUIREMENTS §5.5 and §5.7. Buttons are fully round, so they have no radius token. */
@Immutable
data class SwipeRadii(
    val card: Dp = 24.dp,
    val sheet: Dp = 20.dp,
    val toast: Dp = 12.dp,
    val tile: Dp = 8.dp,
    val stamp: Dp = 8.dp,
)

/** Shared sizes from REQUIREMENTS §5.5. Component-specific values live in [SwipeComponentSizes]. */
@Immutable
data class SwipeSizes(
    val minTouchTarget: Dp = 44.dp,
    val hairline: Dp = 1.dp,
    val icon: Dp = 24.dp,
    val actionBarIcon: Dp = 28.dp,
)

/**
 * Component-specific sizes from REQUIREMENTS §5.7 for the WP-1.2 base
 * components. Shapes are built in the components from `SwipeTheme.radii`
 * tokens (pill/circle buttons per §5.5); only raw numbers that have no other
 * token live here.
 */
@Immutable
data class SwipeComponentSizes(
    // Icons: §5.3 `system_default` is a 17 pt SF Symbol; generic in-row icons are 20 dp (§5.7 rows).
    val systemDefaultIconSize: TextUnit = 17.sp,
    val rowIcon: Dp = 20.dp,
    // SwipeCard (§5.7): full width minus 2 × 8 dp inset, 96 dp bottom scrim.
    val cardInset: Dp = 8.dp,
    val cardScrimHeight: Dp = 96.dp,
    // Top card shadow (§5.5): blur 24 dp, 40% opacity (see CARD_SHADOW_ALPHA).
    val cardShadowBlur: Dp = 24.dp,
    // VerdictStamp (§5.7): 3 dp stroke; its 8/16 dp padding is the spacing scale.
    val stampStrokeWidth: Dp = 3.dp,
    // ActionBar (§5.7): Delete/Keep 64 dp, Skip 48 dp, Undo 40 dp, 24 dp gap.
    val actionButtonLarge: Dp = 64.dp,
    val actionButtonSkip: Dp = 48.dp,
    val actionButtonUndo: Dp = 40.dp,
    val actionBarGap: Dp = 24.dp,
    // TopBar (§5.7): 44 dp tall, 2 dp progress line.
    val topBarHeight: Dp = 44.dp,
    val progressLineThickness: Dp = 2.dp,
    // SourceRow (§5.7): 64 dp row with a 40 dp thumbnail.
    val sourceRowHeight: Dp = 64.dp,
    val sourceRowThumbnail: Dp = 40.dp,
    // GridTile (§5.7): 2 dp gaps, 3 dp keep ring when selected for restore.
    val gridGap: Dp = 2.dp,
    val tileRingWidth: Dp = 3.dp,
    // PrimaryButton (§5.7): 52 dp pill.
    val primaryButtonHeight: Dp = 52.dp,
    // QueueBadge (§5.7): min 20 dp pill, shows 99+ above 99.
    val badgeMinSize: Dp = 20.dp,
    val badgeMaxShown: Int = 99,
    // Banner (§5.7): 44 dp min row, 4 dp leading tone bar.
    val bannerMinHeight: Dp = 44.dp,
    val bannerLeadingBarWidth: Dp = 4.dp,
    // Toast (§5.7): 90% of available width, 3 s auto-dismiss handled in the
    // component. §5.7 fixes only placement, surface, and radius, so the padding
    // follows the §5.5 spacing scale (vertical `s`, horizontal `gutter`).
    val toastWidthFraction: Float = 0.9f,
    val toastPaddingVertical: Dp = 12.dp,
    val toastPaddingHorizontal: Dp = 16.dp,
    // Segmented control (§5.7 filter control): visual segment height; the
    // touch target is at least SwipeSizes.minTouchTarget (44 dp).
    val segmentHeight: Dp = 36.dp,
    // Alpha for disabled content (§5.7 disabled states).
    val disabledAlpha: Float = DISABLED_ALPHA,
    /** Alpha for tone-tinted fills that are not verdict colors (§5.3 uses 16% for verdicts). */
    val tintedFillAlpha: Float = TINTED_FILL_ALPHA,
)

/** Disabled content opacity (§5.7). */
const val DISABLED_ALPHA = 0.4f

/** Verdict-tinted fill opacity (§5.3), mirrored here for non-verdict tones. */
const val TINTED_FILL_ALPHA = 0.16f

/** Soft black shadow opacity on the top card (§5.5: blur 24 dp, 40% opacity). */
const val CARD_SHADOW_ALPHA = 0.40f
