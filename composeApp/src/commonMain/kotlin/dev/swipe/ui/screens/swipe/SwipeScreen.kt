package dev.swipe.ui.screens.swipe

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import dev.swipe.data.MediaLoader
import dev.swipe.domain.model.MediaTypeFilter
import dev.swipe.presentation.swipe.CardState
import dev.swipe.presentation.swipe.SwipeIntent
import dev.swipe.presentation.swipe.SwipeUiState
import dev.swipe.resources.Res
import dev.swipe.resources.ic_arrow_upward
import dev.swipe.resources.ic_check
import dev.swipe.resources.ic_close
import dev.swipe.ui.components.CircleIconButton
import dev.swipe.ui.components.media.rememberMediaImage
import dev.swipe.ui.theme.SwipeTheme
import dev.swipe.ui.theme.VERDICT_TINT_ALPHA

/**
 * Decodes the `Swipe` route's [MediaTypeFilter] name. Unknown or missing
 * names fall back to [MediaTypeFilter.ALL], so a malformed deep link can
 * never crash the deck (a deck over everything is still a valid deck).
 */
fun decodeTypeFilter(name: String): MediaTypeFilter = MediaTypeFilter.entries.firstOrNull { it.name == name } ?: MediaTypeFilter.ALL

/**
 * The M1 deck screen (WP-1.9): the top card over the next card, and the three
 * decision buttons wired straight to [SwipeIntent]s (FR-12–14). Gestures, the
 * TopBar, the metadata scrim, and the animated ActionBar arrive in WP-2.x; the
 * buttons here are the temporary input path and already use the §5.7 ActionBar
 * sizes, gap, and verdict tints so the final screen only adds, not restyles.
 *
 * Stateless by convention (AGENTS.md): one [SwipeUiState] in, committed
 * [SwipeIntent]s out. Pixels never enter the state — each card collects its own
 * image via [rememberMediaImage], degraded first (NFR-1), at
 * [MediaLoader.FULL_TARGET_PX] so the ViewModel's prefetch feeds the same cache
 * key (NFR-3).
 */
@Composable
fun SwipeScreen(
    state: SwipeUiState,
    onIntent: (SwipeIntent) -> Unit,
    mediaLoader: MediaLoader,
    modifier: Modifier = Modifier,
) {
    val colors = SwipeTheme.colors
    val spacing = SwipeTheme.spacing
    val componentSizes = SwipeTheme.componentSizes

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.bg),
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(
                        horizontal = componentSizes.cardInset,
                        vertical = spacing.s,
                    ),
        ) {
            // The next card sits under the top one (FR-11); until WP-2.1's drag
            // the opaque top card simply covers it.
            // Emission order = paint order: the next card first, the top card over it.
            state.next?.let { card ->
                DeckCard(
                    card = card,
                    loader = mediaLoader,
                )
            }
            state.current?.let { card ->
                DeckCard(
                    card = card,
                    loader = mediaLoader,
                )
            }
        }
        // Temporary action bar (§5.7 order and sizes; Undo comes with WP-2.2).
        ActionBar(onIntent = onIntent)
    }
}

/**
 * The three decision buttons in the §5.7 ActionBar order (Delete, Skip, Keep)
 * with its sizes, gap, and verdict tints. WP-2.2 turns this into the animated
 * ActionBar; keeping it separate so the swap touches one composable.
 */
@Composable
private fun ActionBar(onIntent: (SwipeIntent) -> Unit) {
    val colors = SwipeTheme.colors
    val spacing = SwipeTheme.spacing
    val componentSizes = SwipeTheme.componentSizes
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.m),
        horizontalArrangement = Arrangement.spacedBy(componentSizes.actionBarGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            resource = Res.drawable.ic_close,
            label = "Delete",
            onClick = { onIntent(SwipeIntent.Delete) },
            size = componentSizes.actionButtonLarge,
            container = colors.delete.copy(alpha = VERDICT_TINT_ALPHA),
            contentColor = colors.delete,
            iconSize = SwipeTheme.sizes.actionBarIcon,
        )
        CircleIconButton(
            resource = Res.drawable.ic_arrow_upward,
            label = "Skip",
            onClick = { onIntent(SwipeIntent.Skip) },
            size = componentSizes.actionButtonSkip,
            container = colors.surfaceRaised,
            contentColor = colors.skip,
            iconSize = SwipeTheme.sizes.actionBarIcon,
        )
        CircleIconButton(
            resource = Res.drawable.ic_check,
            label = "Keep",
            onClick = { onIntent(SwipeIntent.Keep) },
            size = componentSizes.actionButtonLarge,
            container = colors.keep.copy(alpha = VERDICT_TINT_ALPHA),
            contentColor = colors.keep,
            iconSize = SwipeTheme.sizes.actionBarIcon,
        )
    }
}

/**
 * One card: radius 24 on `bg` with the media aspect-fit inside (§5.7
 * `SwipeCard`). The image is the loader's degraded-then-full pair (NFR-1);
 * while it is null the card is an empty rounded surface. The decorative image
 * stays out of accessibility — the decision buttons carry the VoiceOver labels
 * (NFR-8).
 */
@Composable
private fun DeckCard(
    card: CardState,
    loader: MediaLoader,
) {
    val loaded =
        rememberMediaImage(
            id = card.id,
            targetPx = MediaLoader.FULL_TARGET_PX,
            loader = loader,
        )
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(SwipeTheme.radii.card))
                .background(SwipeTheme.colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        loaded?.let { image ->
            Image(
                bitmap = image.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
