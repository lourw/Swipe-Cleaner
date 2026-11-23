package dev.swipe.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.swipe.ui.theme.SwipeTheme

/** Test tag of the FR-22 favorite confirm card. */
internal const val FAVORITE_CONFIRM_TEST_TAG = "favorite-confirm"

/**
 * The confirm card's entrance fade in ms (§5.7, WP-8.9); under Reduce Motion
 * the card appears at full alpha with no animation. Like the Reduce Motion
 * crossfade in `SwipeSpec`, 150 ms is the app's entrance vocabulary.
 */
internal const val FAVORITE_CONFIRM_ENTRANCE_MS = 150

/**
 * Test hook mirroring `RotationZSemanticsPropertyKey`: the entrance alpha the
 * layer actually applies, published on the card node because graphicsLayer
 * alpha is invisible to semantics otherwise. `FavoriteConfirmCardTest` pins
 * the Reduce Motion case with it.
 */
internal val FavoriteConfirmAlphaSemanticsPropertyKey = SemanticsPropertyKey<Float>("FavoriteConfirmEntranceAlpha")

/**
 * The FR-22 favorite confirm card (§5.7, WP-8.9): the compact card anchored
 * at the bottom of the deck stage directly above the action bar — the same
 * overlay anchor as the deck toast — so the photo being confirmed stays
 * visible and never resizes. It replaces the old confirm toast, which read
 * as an error and offered no way out.
 *
 * The buttons follow the iOS alert convention: **Cancel** left in the
 * secondary (text) style, **Delete** right as the only element in the card
 * wearing the `delete` verdict color, since it is what records the delete.
 * Nothing is pre-selected and nothing auto-accepts (NFR-5): the DELETE is
 * written only after [onDelete] (which carries the confirm intent), and
 * [onCancel] records nothing and keeps the card. The card stays until the
 * user acts, an Undo closes it, or the deck moves on — all three are the
 * owner clearing it.
 *
 * NFR-8: the title is announced when the card appears (Assertive live
 * region, the [Toast] pattern), and the buttons carry full VoiceOver labels
 * ("Cancel, keep this photo", "Delete favorite"). [reduceMotion] skips the
 * entrance fade.
 */
@Composable
fun FavoriteConfirmCard(
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    title: String = "Delete this favorite?",
    message: String = "Queues it with the items waiting to be deleted.",
    cancelLabel: String = "Cancel",
    deleteLabel: String = "Delete",
) {
    // Entrance fade (NFR-8 Reduce Motion: appears at full alpha at once).
    val entrance = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(entrance) {
        if (entrance.value < 1f) entrance.animateTo(1f, tween(FAVORITE_CONFIRM_ENTRANCE_MS))
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SwipeTheme.spacing.xxs),
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = entrance.value }
                .background(
                    SwipeTheme.colors.surfaceRaised,
                    RoundedCornerShape(SwipeTheme.radii.sheet),
                ).padding(SwipeTheme.spacing.m)
                .testTag(FAVORITE_CONFIRM_TEST_TAG)
                .semantics {
                    // NFR-8: VoiceOver announces the title as the card appears.
                    liveRegion = LiveRegionMode.Assertive
                    contentDescription = title
                    set(FavoriteConfirmAlphaSemanticsPropertyKey, entrance.value)
                },
    ) {
        BasicText(
            text = title,
            style =
                SwipeTheme.typography.headline.copy(
                    color = SwipeTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        BasicText(
            text = message,
            style =
                SwipeTheme.typography.callout.copy(
                    color = SwipeTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        // iOS alert convention: equal-width buttons side by side, a hairline
        // between, each cell a full minTouchTarget (44 dp, §5.5). The divider
        // is drawn inside the Delete cell so both cells measure identically.
        Row(modifier = Modifier.fillMaxWidth().padding(top = SwipeTheme.spacing.xs)) {
            ConfirmCardButton(
                label = cancelLabel,
                accessibilityLabel = "$cancelLabel, keep this photo",
                color = SwipeTheme.colors.accent,
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            ConfirmCardButton(
                label = deleteLabel,
                accessibilityLabel = "$deleteLabel favorite",
                color = SwipeTheme.colors.delete,
                onClick = onDelete,
                dividerColor = SwipeTheme.colors.border,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One button cell of the confirm card: centered text in [color] on a
 * minTouchTarget-tall clickable, labelled for VoiceOver with the full
 * [accessibilityLabel] (§5.5, NFR-8). When [dividerColor] is set, a hairline
 * is drawn along the cell's start edge (the iOS alert separator) without
 * taking layout width, so both cells stay exactly equal.
 */
@Composable
private fun ConfirmCardButton(
    label: String,
    accessibilityLabel: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dividerColor: Color? = null,
) {
    val hairline = SwipeTheme.sizes.hairline
    Box(
        modifier =
            modifier
                .heightIn(min = SwipeTheme.sizes.minTouchTarget)
                .clip(RoundedCornerShape(SwipeTheme.radii.toast))
                .drawBehind {
                    if (dividerColor != null) {
                        drawLine(
                            color = dividerColor,
                            start = Offset.Zero,
                            end = Offset(0f, size.height),
                            strokeWidth = hairline.toPx(),
                        )
                    }
                }.clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = accessibilityLabel
                },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = SwipeTheme.typography.headline.copy(color = color),
        )
    }
}
